package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.RoundStatus

/**
 * RTB payload processor for loading highest-eCPM cached RTB payloads.
 *
 * Loads only the best (highest eCPM) RTB payload from RtbPayloadCache per auction.
 * On success, stores in ReadyToShowCache. On failure, removes invalid payload from cache.
 *
 * Thread-safety: Injected coroutine scope ensures proper cancellation support.
 * Failure handling: Invalid payloads removed from cache to prevent retry of broken bids.
 */
internal class RtbProcessor(
    private val adaptersSource: AdaptersSource,
    private val regulation: Regulation,
) {
    /**
     * Load the highest-eCPM RTB payload from cache with retry logic.
     *
     * Process:
     * 1. Get all payloads from RtbPayloadCache (sorted by eCPM descending)
     * 2. Try each payload until success or exhaustion (retry logic)
     * 3. Check cancellation before each load attempt
     * 4. Create AdSource and load the ad
     * 5. On success: Store in ReadyToShowCache, exit loop
     * 6. On failure: Remove invalid payload from RtbPayloadCache, continue to next
     * 7. Always destroy AdSource in finally block unless successfully loaded
     *
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @return Result with AuctionResult on success, BidonError on failure
     */
    suspend fun loadBestPayload(
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
    ): Result<AuctionResult> = coroutineScope {
        // Get all payloads from cache (sorted by eCPM descending)
        val payloads = RtbPayloadCache.getAllSortedByEcpm()

        if (payloads.isEmpty()) {
            logInfo(TAG, "RTB cache empty, no payloads to load")
            return@coroutineScope Result.failure(BidonError.NoFill(DemandId("RTB")))
        }

        logInfo(TAG, "RTB retry: ${payloads.size} payloads available, trying in eCPM order")

        // Try each payload until success (retry logic for RTB-03)
        for (payload in payloads) {
            // Check cancellation before each load attempt
            ensureActive()

            val demandId = payload.value.adUnit.demandId
            val ecpm = payload.ecpm

            logInfo(TAG, "RTB loading: demandId=$demandId, ecpm=$ecpm")

            // Find adapter by demandId
            val adapter = adaptersSource.adapters.find { it.demandId.demandId == demandId }
            if (adapter == null) {
                logInfo(TAG, "Adapter not found for demandId=$demandId, trying next payload")
                RtbPayloadCache.remove(demandId)
                continue // Retry next payload
            }

            // Apply regulation
            adapter.applyRegulation()

            // Create AdSource
            val adSource = createAdSource(adapter, demandAd, adTypeParam)
            if (adSource == null) {
                logInfo(TAG, "AdSource creation failed for demandId=$demandId, trying next payload")
                RtbPayloadCache.remove(demandId)
                continue // Retry next payload
            }

            // Track if ad loaded successfully for cleanup decision
            var loadSuccess = false

            try {
                // Set token info from payload
                payload.value.tokenInfo?.let { tokenInfo ->
                    adSource.setTokenInfo(tokenInfo)
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
                        adUnit = payload.value.adUnit,
                    )
                ).getOrNull()

                if (adParams == null) {
                    logInfo(TAG, "RTB load failed: demandId=$demandId, failed to create auction params, trying next payload")
                    RtbPayloadCache.remove(demandId)
                    continue // Retry next payload
                }

                // Load ad with timeout
                val adEvent = withTimeout(payload.value.adUnit.timeout) {
                    adSource.load(adParams)
                    adSource.adEvent.first { event ->
                        event is AdEvent.Fill || event is AdEvent.LoadFailed || event is AdEvent.Expired
                    }
                }

                when (adEvent) {
                    is AdEvent.Fill -> {
                        logInfo(TAG, "RTB loaded successfully: demandId=$demandId")
                        loadSuccess = true // Mark as success to prevent destroy in finally

                        val auctionResult: AuctionResult = AuctionResult.Bidding(adSource, RoundStatus.Successful)

                        // Store in ReadyToShowCache
                        val cacheEntry: CacheEntry<AuctionResult> = CacheEntry.create(
                            value = auctionResult,
                            ecpm = ecpm,
                            demandId = demandId,
                            auctionId = auctionId
                        )
                        ReadyToShowCache.put(cacheEntry)

                        // Remove from RTB_PAYLOAD cache (successfully loaded)
                        RtbPayloadCache.remove(demandId)

                        return@coroutineScope Result.success(auctionResult)
                    }
                    is AdEvent.LoadFailed, is AdEvent.Expired -> {
                        val error = when (adEvent) {
                            is AdEvent.LoadFailed -> adEvent.cause
                            is AdEvent.Expired -> BidonError.Expired(null)
                            else -> BidonError.NoFill(DemandId(demandId))
                        }
                        logInfo(TAG, "RTB load failed: demandId=$demandId, removing from cache, trying next payload, error=$error")
                        RtbPayloadCache.remove(demandId)
                        // Continue to next payload (retry)
                    }
                    else -> {
                        logError(TAG, "Unexpected ad event: $adEvent", null)
                        RtbPayloadCache.remove(demandId)
                        // Continue to next payload (retry)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // NEVER catch CancellationException - always rethrow
                throw e
            } catch (e: Exception) {
                logInfo(TAG, "RTB load failed: demandId=$demandId, removing from cache, trying next payload, exception=${e.message}")
                RtbPayloadCache.remove(demandId)
                // Continue to next payload (retry)
            } finally {
                // Destroy AdSource if not successfully loaded (cleanup on failure)
                if (!loadSuccess) {
                    adSource.destroy()
                }
            }
        }

        // All payloads exhausted
        logInfo(TAG, "RTB retry exhausted: all payloads failed")
        return@coroutineScope Result.failure(BidonError.NoFill(DemandId("RTB")))
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

private const val TAG = "RtbProcessor"
