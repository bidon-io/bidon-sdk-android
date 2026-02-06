package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.denis.lifecycle.CleanupCoordinator
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.RoundStatus

/**
 * CPM waterfall processor for sequential ad loading with dynamic weight model.
 *
 * Loads CPM adUnits sequentially (one at a time) in weighted eCPM order.
 * Each successful load goes to ReadyToShowCache.
 * Fill/no-fill feedback updates WeightModel for future optimizations.
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 * Failure handling: Continues through entire waterfall (doesn't stop on first success).
 */
internal class CpmProcessor(
    private val adaptersSource: AdaptersSource,
    private val regulation: Regulation,
    private val weightModel: WeightModel = WeightModel, // Singleton default
) {
    /**
     * Load entire CPM waterfall sequentially.
     *
     * Process:
     * 1. Sort adUnits by WeightModel.sortByWeightedScore() (eCPM × weight factor)
     * 2. Load each adUnit sequentially (one at a time)
     * 3. Record fill/no-fill on WeightModel after each attempt
     * 4. On success: Store in ReadyToShowCache
     * 5. Continue through entire waterfall (don't stop early)
     *
     * @param adUnits List of CPM ad units to load
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @return CpmWaterfallResult with success/failure counts
     */
    suspend fun loadWaterfall(
        adUnits: List<AdUnit>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
    ): CpmWaterfallResult {
        // Sort by weighted score (eCPM × weight factor)
        val sortedAdUnits = weightModel.sortByWeightedScore(adUnits)

        var successCount = 0
        var failureCount = 0
        var firstSuccess: AuctionResult? = null

        logInfo(TAG, "CPM waterfall loading: ${sortedAdUnits.size} ad units")

        for (adUnit in sortedAdUnits) {
            // Check cancellation before each load
            kotlinx.coroutines.coroutineScope {
                ensureActive()
            }

            val weight = weightModel.getWeight(adUnit.demandId)
            val score = weightModel.calculateScore(adUnit)
            logInfo(TAG, "CPM loading: demandId=${adUnit.demandId}, ecpm=${adUnit.pricefloor}, weight=$weight, score=$score")

            val result = loadSingleAdUnit(
                adUnit = adUnit,
                adTypeParam = adTypeParam,
                demandAd = demandAd,
                auctionId = auctionId,
                auctionConfigurationId = auctionConfigurationId,
                auctionConfigurationUid = auctionConfigurationUid,
                externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                pricefloor = pricefloor,
            )

            if (result.isSuccess) {
                weightModel.recordFill(adUnit.demandId)
                successCount++
                logInfo(TAG, "CPM loaded successfully: demandId=${adUnit.demandId}")

                if (firstSuccess == null) {
                    firstSuccess = result.getOrNull()
                }
            } else {
                weightModel.recordNoFill(adUnit.demandId)
                failureCount++
                val error = result.exceptionOrNull()
                logInfo(TAG, "CPM load failed: demandId=${adUnit.demandId}, continuing waterfall, error=$error")
                // Continue to next adUnit (don't stop waterfall)
            }
        }

        logInfo(TAG, "CPM waterfall complete: success=$successCount, failure=$failureCount")

        return CpmWaterfallResult(
            successCount = successCount,
            failureCount = failureCount,
            firstSuccess = firstSuccess,
        )
    }

    /**
     * Load single CPM ad unit.
     *
     * Process:
     * 1. Find adapter by demandId
     * 2. Create AdSource for ad type
     * 3. Apply auction parameters
     * 4. Load ad with timeout
     * 5. On success: Store in ReadyToShowCache
     * 6. On failure: Return failure (don't throw)
     * 7. Always destroy AdSource in finally block if not ready
     *
     * @param adUnit Ad unit to load
     * @param adTypeParam Ad type parameters
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @return Result with AuctionResult on success, BidonError on failure
     */
    private suspend fun loadSingleAdUnit(
        adUnit: AdUnit,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
    ): Result<AuctionResult> {
        var adSource: AdSource<AdAuctionParams>? = null

        return try {
            // Find adapter by demandId
            val adapter = adaptersSource.adapters.find { it.demandId.demandId == adUnit.demandId }
            if (adapter == null) {
                logInfo(TAG, "Adapter not found for demandId=${adUnit.demandId}")
                return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
            }

            // Apply regulation
            adapter.applyRegulation()

            // Create AdSource
            adSource = createAdSource(adapter, demandAd, adTypeParam)
            if (adSource == null) {
                logInfo(TAG, "AdSource creation failed for demandId=${adUnit.demandId}")
                return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
            }

            // Apply auction parameters
            applyParams(
                adSource = adSource,
                auctionId = auctionId,
                auctionConfigurationId = auctionConfigurationId,
                auctionConfigurationUid = auctionConfigurationUid,
                externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                demandAd = demandAd,
                pricefloor = pricefloor,
            )

            // Get auction params
            val adParams = adSource.getAuctionParam(
                org.bidon.sdk.adapter.AdAuctionParamSource(
                    activity = adTypeParam.activity,
                    pricefloor = pricefloor,
                    optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    adUnit = adUnit,
                )
            ).getOrNull()

            if (adParams == null) {
                logInfo(TAG, "CPM load failed: demandId=${adUnit.demandId}, failed to create auction params")
                adSource.destroy()
                return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
            }

            // Mark fill started (sets adUnit in stats for getAd() calls)
            adSource.markFillStarted(adUnit, pricefloor)

            // Load ad with timeout (on Main thread for adapters like Admob)
            val adEvent = withTimeout(adUnit.timeout) {
                withContext(Dispatchers.Main) {
                    adSource.load(adParams)
                }
                adSource.adEvent.first { event ->
                    event is AdEvent.Fill || event is AdEvent.LoadFailed || event is AdEvent.Expired
                }
            }

            when (adEvent) {
                is AdEvent.Fill -> {
                    // Store in ReadyToShowCache
                    val auctionResult: AuctionResult = AuctionResult.Network(adSource, RoundStatus.Successful)
                    val entry: CacheEntry<AuctionResult> = CacheEntry.create(
                        value = auctionResult,
                        ecpm = adUnit.pricefloor, // Use waterfall eCPM, not actual bid price
                        demandId = adUnit.demandId,
                        auctionId = auctionId
                    )
                    ReadyToShowCache.put(entry)
                    Result.success(auctionResult)
                }
                is AdEvent.LoadFailed, is AdEvent.Expired -> {
                    val error = when (adEvent) {
                        is AdEvent.LoadFailed -> adEvent.cause
                        is AdEvent.Expired -> BidonError.Expired(null)
                        else -> BidonError.NoFill(DemandId(adUnit.demandId))
                    }
                    Result.failure(error)
                }
                else -> {
                    logError(TAG, "Unexpected ad event: $adEvent", null)
                    Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NEVER catch CancellationException - always rethrow
            throw e
        } catch (e: Exception) {
            logInfo(TAG, "CPM load exception: demandId=${adUnit.demandId}, exception=${e.message}")
            Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
        } finally {
            // Guaranteed cleanup even if cancelled (LIFE-06)
            // Only destroy if not successfully loaded into cache
            if (adSource != null && adSource.isAdReadyToShow != true) {
                CleanupCoordinator.destroyAdSource(adSource, adUnit.demandId)
            }
        }
    }

    /**
     * Create AdSource from adapter based on ad type.
     *
     * @param adapter Adapter instance
     * @param demandAd Demand ad configuration
     * @param adTypeParam Ad type parameters
     * @return AdSource instance or null if adapter doesn't support the ad type
     */
    private fun createAdSource(
        adapter: Adapter,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): AdSource<AdAuctionParams>? {
        val adapterDemandId = adapter.demandId
        return when (demandAd.adType) {
            AdType.Interstitial -> {
                (adapter as? AdProvider.Interstitial<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.interstitial().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create interstitial ad source", it)
                    }.getOrNull()
                }
            }
            AdType.Rewarded -> {
                (adapter as? AdProvider.Rewarded<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.rewarded().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create rewarded ad source", it)
                    }.getOrNull()
                }
            }
            AdType.Banner -> {
                (adapter as? AdProvider.Banner<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.banner().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create banner ad source", it)
                    }.getOrNull()
                }
            }
        }
    }

    /**
     * Apply auction parameters to AdSource.
     *
     * @param adSource AdSource instance
     * @param auctionId Auction identifier
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param demandAd Demand ad configuration
     * @param pricefloor Minimum acceptable price
     */
    private fun applyParams(
        adSource: AdSource<AdAuctionParams>,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        demandAd: DemandAd,
        pricefloor: Double,
    ) {
        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = pricefloor,
        )
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }
}

/**
 * Result of CPM waterfall loading.
 *
 * @property successCount Number of successfully loaded ads
 * @property failureCount Number of failed loads
 * @property firstSuccess First successfully loaded ad (highest priority)
 */
internal data class CpmWaterfallResult(
    val successCount: Int,
    val failureCount: Int,
    val firstSuccess: AuctionResult?, // First successfully loaded ad
)

private const val TAG = "CpmProcessor"
