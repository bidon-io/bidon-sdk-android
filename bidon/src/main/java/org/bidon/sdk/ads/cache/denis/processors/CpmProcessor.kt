package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
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
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.cache.denis.lifecycle.CleanupCoordinator
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.RoundStatus
import kotlin.coroutines.coroutineContext

/**
 * CPM waterfall processor with batch loading and early-stop optimization.
 *
 * Loads CPM adUnits in batches of [BATCH_SIZE] (2 at a time), sorted by pricefloor descending.
 * Within each batch, ad units load in parallel.
 * Stops after the first successful fill OR when the batch's lowest pricefloor
 * is less than or equal to the best cached RTB eCPM in ReadyToShowCache.
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 */
internal class CpmProcessor(
    private val adaptersSource: AdaptersSource,
) {
    /**
     * Load CPM waterfall in batches of [BATCH_SIZE] with early-stop logic.
     *
     * Process:
     * 1. Sort adUnits by pricefloor descending (highest first)
     * 2. Chunk into batches of [BATCH_SIZE]
     * 3. For each batch:
     *    a. Check if batch's highest pricefloor <= best cached eCPM -> STOP
     *    b. Load all ads in the batch in parallel
     *    c. On first success -> fire callback -> STOP
     *
     * @param adUnits List of CPM ad units to load
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @param resultsCollector Collector for auction results
     * @param onFirstFill Callback to fire on first successful load (for immediate onAdLoaded)
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
        resultsCollector: ResultsCollector,
        onFirstFill: (AuctionResult) -> Unit = {},
    ): CpmWaterfallResult {
        // Sort by pricefloor descending (highest first)
        val sortedAdUnits = adUnits.sortedByDescending { it.pricefloor }
        val batches = sortedAdUnits.chunked(BATCH_SIZE)

        var successCount = 0
        var failureCount = 0
        var firstSuccess: AuctionResult? = null

        logInfo(TAG, "Loading ${sortedAdUnits.size} CPM units in ${batches.size} batches of $BATCH_SIZE")

        for ((batchIndex, batch) in batches.withIndex()) {
            // Check cancellation before each batch
            coroutineContext.ensureActive()

            // Early stop: if batch's highest pricefloor <= best cached eCPM, stop
            val bestCachedEcpm = ReadyToShowCache.getMaxEcpm()
            val batchHighestPricefloor = batch.first().pricefloor
            if (bestCachedEcpm > 0.0 && batchHighestPricefloor <= bestCachedEcpm) {
                logInfo(
                    TAG,
                    "CPM early stop: batch[$batchIndex] highest pricefloor=${"$%.2f".format(batchHighestPricefloor)} <= " +
                        "best cached eCPM=${"$%.2f".format(bestCachedEcpm)}, stopping waterfall"
                )
                break
            }

            logInfo(
                TAG,
                "CPM batch[$batchIndex]: ${batch.size} units [${batch.joinToString { "${it.demandId}:$${"%.2f".format(it.pricefloor)}" }}]"
            )

            // Load all ads in this batch in parallel
            val batchResults = supervisorScope {
                batch.map { adUnit ->
                    async {
                        adUnit to loadSingleAdUnit(
                            adUnit = adUnit,
                            adTypeParam = adTypeParam,
                            demandAd = demandAd,
                            auctionId = auctionId,
                            auctionConfigurationId = auctionConfigurationId,
                            auctionConfigurationUid = auctionConfigurationUid,
                            externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                            pricefloor = pricefloor,
                            resultsCollector = resultsCollector,
                        )
                    }
                }.map { deferred ->
                    runCatching { deferred.await() }.getOrElse { e ->
                        // Shouldn't happen with supervisorScope, but handle gracefully
                        null
                    }
                }
            }

            // Process batch results — pick best success (highest pricefloor)
            var batchBestSuccess: AuctionResult? = null
            var batchBestPricefloor = 0.0

            for (entry in batchResults) {
                if (entry == null) {
                    failureCount++
                    continue
                }
                val (adUnit, result) = entry
                if (result.isSuccess) {
                    successCount++
                    val auctionResult = result.getOrNull()
                    if (auctionResult != null && adUnit.pricefloor > batchBestPricefloor) {
                        batchBestSuccess = auctionResult
                        batchBestPricefloor = adUnit.pricefloor
                    }
                } else {
                    failureCount++
                }
            }

            if (batchBestSuccess != null) {
                if (firstSuccess == null) {
                    firstSuccess = batchBestSuccess
                    onFirstFill(batchBestSuccess)
                }
                logInfo(TAG, "CPM batch[$batchIndex] fill, stopping waterfall")
                break
            }
        }

        logInfo(TAG, "CPM complete: success=$successCount, failed=$failureCount")

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
     * @param resultsCollector Collector for auction results
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
        resultsCollector: ResultsCollector,
    ): Result<AuctionResult> {
        var adSource: AdSource<AdAuctionParams>? = null

        return try {
            // Find adapter by demandId
            val adapter = adaptersSource.adapters.find { it.demandId.demandId == adUnit.demandId }
                ?: run {
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.UnknownAdapter, tokenInfo = null
                    )
                    resultsCollector.add(failedResult)
                    return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }

            // Apply regulation
            adapter.applyRegulation()

            // Create AdSource
            adSource = createAdSource(adapter, demandAd, adTypeParam)
                ?: run {
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    resultsCollector.add(failedResult)
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
                adTypeParam = adTypeParam,
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
                adSource.destroy()
                val failedResult = AuctionResult.AuctionFailed(
                    adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                )
                resultsCollector.add(failedResult)
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
                    // Update price to waterfall eCPM
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.Successful,
                        price = adUnit.pricefloor // For CPM use waterfall eCPM
                    )

                    // Store in ReadyToShowCache
                    val auctionResult: AuctionResult = AuctionResult.Network(adSource, RoundStatus.Successful)

                    // Report successful CPM result to ResultsCollector
                    resultsCollector.add(auctionResult)

                    val entry: CacheEntry<AuctionResult> = CacheEntry.create(
                        value = auctionResult,
                        ecpm = adUnit.pricefloor, // Use waterfall eCPM, not actual bid price
                        demandId = adUnit.demandId,
                        auctionId = auctionId,
                        uid = adUnit.uid
                    )
                    ReadyToShowCache.put(entry)
                    logInfo(TAG, "→ READY_TO_SHOW: stored ${adUnit.demandId} $${"%.2f".format(adUnit.pricefloor)}")
                    Result.success(auctionResult)
                }
                is AdEvent.LoadFailed, is AdEvent.Expired -> {
                    val error = when (adEvent) {
                        is AdEvent.LoadFailed -> adEvent.cause
                        is AdEvent.Expired -> BidonError.Expired(null)
                        else -> BidonError.NoFill(DemandId(adUnit.demandId))
                    }
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    resultsCollector.add(failedResult)
                    Result.failure(error)
                }
                else -> {
                    logError(TAG, "Unexpected ad event: $adEvent", null)
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    resultsCollector.add(failedResult)
                    Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NEVER catch CancellationException - always rethrow
            throw e
        } catch (e: Exception) {
            val failedResult = AuctionResult.AuctionFailed(
                adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
            )
            resultsCollector.add(failedResult)
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
                    }.getOrNull()
                }
            }
            AdType.Rewarded -> {
                (adapter as? AdProvider.Rewarded<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.rewarded().apply { addDemandId(adapterDemandId) }
                    }.getOrNull()
                }
            }
            AdType.Banner -> {
                (adapter as? AdProvider.Banner<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.banner().apply { addDemandId(adapterDemandId) }
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
        adTypeParam: AdTypeParam,
    ) {
        // Set statistic ad type (CRITICAL: must be set before show)
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())

        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = pricefloor,
        )
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }

    /**
     * Convert AdTypeParam to StatisticsCollector.AdType.
     */
    private fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType {
        return when (this) {
            is AdTypeParam.Banner -> {
                StatisticsCollector.AdType.Banner(
                    format = when (bannerFormat) {
                        BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                        BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                        BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                        BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                    }
                )
            }
            is AdTypeParam.Interstitial -> StatisticsCollector.AdType.Interstitial
            is AdTypeParam.Rewarded -> StatisticsCollector.AdType.Rewarded
        }
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

private const val TAG = "[DenisCache] CPM"
private const val BATCH_SIZE = 2
