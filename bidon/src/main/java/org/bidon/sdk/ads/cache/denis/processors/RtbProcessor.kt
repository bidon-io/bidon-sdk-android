package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
import org.bidon.sdk.ads.cache.denis.stores.RtbPayload
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.RoundStatus

/**
 * RTB source representation - either from current auction or from cache.
 */
private sealed class RtbSource {
    abstract val ecpm: Double
    abstract val demandId: String
    abstract val adUnit: org.bidon.sdk.auction.models.AdUnit
    abstract val tokenInfo: org.bidon.sdk.auction.models.TokenInfo?

    /**
     * RTB AdUnit from current auction response.
     * tokenInfo is null because tokens were already used to obtain this AdUnit from server.
     */
    data class FromAdUnit(val unit: org.bidon.sdk.auction.models.AdUnit) : RtbSource() {
        override val ecpm: Double get() = unit.pricefloor
        override val demandId: String get() = unit.demandId
        override val adUnit: org.bidon.sdk.auction.models.AdUnit get() = unit
        override val tokenInfo: org.bidon.sdk.auction.models.TokenInfo? get() = null
    }

    /**
     * RTB payload from RtbPayloadCache.
     */
    data class FromCache(val entry: CacheEntry<RtbPayload>) : RtbSource() {
        override val ecpm: Double get() = entry.ecpm
        override val demandId: String get() = entry.demandId
        override val adUnit: org.bidon.sdk.auction.models.AdUnit get() = entry.value.adUnit
        override val tokenInfo: org.bidon.sdk.auction.models.TokenInfo? get() = entry.value.tokenInfo
    }
}

/**
 * RTB payload processor for loading highest-eCPM RTB payloads.
 *
 * Merges RTB AdUnits from current auction with cached payloads, sorts by eCPM,
 * loads the best one, and caches the rest for future auctions.
 *
 * Thread-safety: Injected coroutine scope ensures proper cancellation support.
 * Failure handling: Invalid payloads removed from cache to prevent retry of broken bids.
 */
internal class RtbProcessor(
    private val adaptersSource: AdaptersSource,
    private val regulation: Regulation,
) {
    /**
     * Load the highest-eCPM RTB payload with retry logic.
     *
     * Process:
     * 1. Merge RTB AdUnits from current auction with cached payloads
     * 2. Sort by eCPM descending (cached payloads use pricefloor, new AdUnits use pricefloor)
     * 3. Try each payload until success or exhaustion (retry logic)
     * 4. Check cancellation before each load attempt
     * 5. Create AdSource and load the ad
     * 6. On success: Store in ReadyToShowCache, save remaining to RtbPayloadCache
     * 7. On failure: Remove invalid payload from RtbPayloadCache, continue to next
     * 8. Always destroy AdSource in finally block unless successfully loaded
     *
     * @param rtbAdUnits RTB ad units from current auction response
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @param onFirstFill Callback to fire on successful load (for immediate onAdLoaded)
     * @return Result with AuctionResult on success, BidonError on failure
     */
    suspend fun loadBestPayload(
        rtbAdUnits: List<org.bidon.sdk.auction.models.AdUnit>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
        onFirstFill: (AuctionResult) -> Unit = {},
    ): Result<AuctionResult> = coroutineScope {
        // Get cached payloads (sorted by eCPM descending)
        val cachedPayloads = RtbPayloadCache.getAllSortedByEcpm()

        // Merge new RTB AdUnits with cached payloads and sort by eCPM
        // New AdUnits converted to RtbSource entries
        val newRtbSources = rtbAdUnits.map { adUnit ->
            RtbSource.FromAdUnit(adUnit)
        }
        val cachedRtbSources = cachedPayloads.map { entry ->
            RtbSource.FromCache(entry)
        }

        val allRtbSources = (newRtbSources + cachedRtbSources)
            .sortedByDescending { it.ecpm }

        if (allRtbSources.isEmpty()) {
            logInfo(TAG, "RTB empty: no AdUnits from auction, no cached payloads")
            return@coroutineScope Result.failure(BidonError.NoFill(DemandId("RTB")))
        }

        logInfo(
            TAG,
            "RTB retry: ${allRtbSources.size} sources (${newRtbSources.size} new, " +
                "${cachedRtbSources.size} cached), trying in eCPM order"
        )

        // Try each source until success (retry logic for RTB-03)
        var loadedIndex = -1
        for ((index, source) in allRtbSources.withIndex()) {
            // Check cancellation before each load attempt
            ensureActive()

            val demandId = source.demandId
            val ecpm = source.ecpm
            val sourceType = when (source) {
                is RtbSource.FromAdUnit -> "new"
                is RtbSource.FromCache -> "cached"
            }

            logInfo(TAG, "RTB loading: demandId=$demandId, ecpm=$ecpm, source=$sourceType")

            // Find adapter by demandId
            val adapter = adaptersSource.adapters.find { it.demandId.demandId == demandId }
            if (adapter == null) {
                logInfo(TAG, "Adapter not found for demandId=$demandId, trying next source")
                // Remove from cache if it was cached
                if (source is RtbSource.FromCache) {
                    RtbPayloadCache.remove(demandId)
                }
                continue // Retry next source
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
                // Set token info from source
                source.tokenInfo?.let { tokenInfo ->
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
                        adUnit = source.adUnit,
                    )
                ).getOrNull()

                if (adParams == null) {
                    logInfo(TAG, "RTB load failed: demandId=$demandId, failed to create auction params, trying next source")
                    if (source is RtbSource.FromCache) {
                        RtbPayloadCache.remove(demandId)
                    }
                    continue // Retry next source
                }

                // Mark fill started (sets adUnit in stats for getAd() calls)
                adSource.markFillStarted(source.adUnit, pricefloor)

                // Load ad with timeout (on Main thread for adapters like Admob)
                val adEvent = withTimeout(source.adUnit.timeout) {
                    withContext(Dispatchers.Main) {
                        adSource.load(adParams)
                    }
                    adSource.adEvent.first { event ->
                        event is AdEvent.Fill || event is AdEvent.LoadFailed || event is AdEvent.Expired
                    }
                }

                when (adEvent) {
                    is AdEvent.Fill -> {
                        logInfo(TAG, "RTB loaded successfully: demandId=$demandId, source=$sourceType")
                        loadSuccess = true // Mark as success to prevent destroy in finally
                        loadedIndex = index // Track which source was loaded

                        val auctionResult: AuctionResult = AuctionResult.Bidding(adSource, RoundStatus.Successful)

                        // Store in ReadyToShowCache
                        val cacheEntry: CacheEntry<AuctionResult> = CacheEntry.create(
                            value = auctionResult,
                            ecpm = ecpm,
                            demandId = demandId,
                            auctionId = auctionId,
                            uid = source.adUnit.uid
                        )
                        ReadyToShowCache.put(cacheEntry)
                        logInfo(TAG, "→ READY_TO_SHOW: stored $demandId $${"%.2f".format(ecpm)}")

                        // Fire callback on successful load (for immediate onAdLoaded)
                        onFirstFill(auctionResult)

                        // Remove loaded source from cache if it was cached
                        if (source is RtbSource.FromCache) {
                            RtbPayloadCache.remove(demandId)
                        }

                        // Save remaining sources to RtbPayloadCache for next auction
                        val remainingSources = allRtbSources.drop(index + 1)
                        var cachedCount = 0
                        remainingSources.forEach { remainingSource ->
                            // Only cache new AdUnits, skip already cached ones
                            if (remainingSource is RtbSource.FromAdUnit) {
                                val payload = RtbPayload(
                                    adUnit = remainingSource.adUnit,
                                    tokenInfo = remainingSource.tokenInfo,
                                    auctionId = auctionId
                                )
                                val inserted = RtbPayloadCache.putIfHigherEcpm(payload)
                                if (inserted) {
                                    cachedCount++
                                    logInfo(TAG, "→ RTB_PAYLOAD: cached ${remainingSource.demandId} $${"%.2f".format(remainingSource.ecpm)}")
                                }
                            }
                        }
                        logInfo(TAG, "RTB summary: loaded 1 ($demandId), cached $cachedCount payloads for future")

                        return@coroutineScope Result.success(auctionResult)
                    }
                    is AdEvent.LoadFailed, is AdEvent.Expired -> {
                        val error = when (adEvent) {
                            is AdEvent.LoadFailed -> adEvent.cause
                            is AdEvent.Expired -> BidonError.Expired(null)
                            else -> BidonError.NoFill(DemandId(demandId))
                        }
                        logInfo(TAG, "RTB load failed: demandId=$demandId, removing from cache, trying next source, error=$error")
                        if (source is RtbSource.FromCache) {
                            RtbPayloadCache.remove(demandId)
                        }
                        // Continue to next source (retry)
                    }
                    else -> {
                        logError(TAG, "Unexpected ad event: $adEvent", null)
                        if (source is RtbSource.FromCache) {
                            RtbPayloadCache.remove(demandId)
                        }
                        // Continue to next source (retry)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // NEVER catch CancellationException - always rethrow
                throw e
            } catch (e: Exception) {
                logInfo(TAG, "RTB load failed: demandId=$demandId, removing from cache, trying next source, exception=${e.message}")
                if (source is RtbSource.FromCache) {
                    RtbPayloadCache.remove(demandId)
                }
                // Continue to next source (retry)
            } finally {
                // Guaranteed cleanup even if cancelled (LIFE-06)
                // Only destroy if load was not successful
                if (!loadSuccess && adSource != null) {
                    CleanupCoordinator.destroyAdSource(adSource, demandId)
                }
            }
        }

        // All sources exhausted - save ALL new AdUnits to cache for next auction
        if (loadedIndex == -1) {
            logInfo(TAG, "RTB retry exhausted: all sources failed, caching new AdUnits for next auction")
            newRtbSources.forEach { newSource ->
                val payload = RtbPayload(
                    adUnit = newSource.adUnit,
                    tokenInfo = newSource.tokenInfo,
                    auctionId = auctionId
                )
                val inserted = RtbPayloadCache.putIfHigherEcpm(payload)
                if (inserted) {
                    logInfo(TAG, "RTB cached (after fail): demandId=${newSource.demandId}, ecpm=${newSource.ecpm}")
                }
            }
        }

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
