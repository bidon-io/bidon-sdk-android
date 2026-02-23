package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.RtbPayload
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
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
 * RTB payload processor with waterfall fallback.
 *
 * Merges RTB AdUnits from current auction with cached payloads, sorts by eCPM,
 * and tries each source in order until one fills successfully.
 * Unfilled sources from current auction are cached for future auctions.
 *
 * Thread-safety: Injected coroutine scope ensures proper cancellation support.
 * Failure handling: Invalid payloads removed from cache to prevent retry of broken bids.
 */
internal class RtbProcessor(
    private val adaptersSource: AdaptersSource,
) {
    /**
     * Load RTB payload with waterfall fallback (try next on failure).
     *
     * Process:
     * 1. Merge RTB AdUnits from current auction with cached payloads
     * 2. Sort by eCPM descending
     * 3. Try each source in order — on failure, fallback to next
     * 4. Cache untried new AdUnits in RtbPayloadCache for future auctions
     * 5. On success: Return CacheEntry (orchestrator handles cache insertion)
     * 6. On failure: Remove from RtbPayloadCache if it was cached
     *
     * @param rtbAdUnits RTB ad units from current auction response
     * @param params Common auction parameters
     * @return Result with Pair of AuctionResult and CacheEntry on success, BidonError on failure
     */
    suspend fun loadBestPayload(
        rtbAdUnits: List<org.bidon.sdk.auction.models.AdUnit>,
        params: AuctionParams,
    ): Result<Pair<AuctionResult, CacheEntry<AuctionResult>>> = coroutineScope {
        // Get cached payloads (sorted by eCPM descending)
        val cachedPayloads = RtbPayloadCache.getAllSortedByEcpm()

        // Merge new RTB AdUnits with cached payloads and sort by eCPM
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
            "RTB: ${allRtbSources.size} sources (${newRtbSources.size} new, " +
                "${cachedRtbSources.size} cached), waterfall order: " +
                allRtbSources.joinToString { "${it.demandId}@$${"%.2f".format(it.ecpm)}" }
        )

        // Track which sources we tried (to cache the rest)
        val triedDemandIds = mutableSetOf<String>()

        for (source in allRtbSources) {
            // Check cancellation before each attempt
            ensureActive()

            triedDemandIds.add(source.demandId)

            val result = tryLoadRtbSource(
                source = source,
                params = params,
            )

            if (result.isSuccess) {
                val (auctionResult, cacheEntry) = result.getOrThrow()

                // Cache untried new AdUnits for future auctions
                val cachedCount = cacheUntriedSources(allRtbSources, triedDemandIds, params.auctionId)

                logInfo(TAG, "RTB summary: loaded ${source.demandId}, cached $cachedCount payloads for future")
                return@coroutineScope Result.success(auctionResult to cacheEntry)
            }
            // Failed — continue to next source
        }

        // All sources failed — cache any untried new AdUnits (shouldn't be any, but safe)
        cacheUntriedSources(allRtbSources, triedDemandIds, params.auctionId)

        return@coroutineScope Result.failure(BidonError.NoFill(DemandId("RTB")))
    }

    /**
     * Try to load a single RTB source.
     *
     * @return Result with AuctionResult on success, BidonError on failure
     */
    private suspend fun tryLoadRtbSource(
        source: RtbSource,
        params: AuctionParams,
    ): Result<Pair<AuctionResult, CacheEntry<AuctionResult>>> {
        val demandId = source.demandId
        val ecpm = source.ecpm

        // Find adapter by demandId
        val adapter = adaptersSource.adapters.find { it.demandId.demandId == demandId }
        if (adapter == null) {
            logInfo(TAG, "Adapter not found for demandId=$demandId")
            if (source is RtbSource.FromCache) {
                RtbPayloadCache.remove(demandId)
            }
            return Result.failure(BidonError.NoFill(DemandId(demandId)))
        }

        // Apply regulation
        adapter.applyRegulation()

        // Create AdSource
        val adSource = AdSourceFactory.createAdSource(adapter, params.demandAd, params.adTypeParam, TAG)
        if (adSource == null) {
            logInfo(TAG, "AdSource creation failed for demandId=$demandId")
            if (source is RtbSource.FromCache) {
                RtbPayloadCache.remove(demandId)
            }
            return Result.failure(BidonError.NoFill(DemandId(demandId)))
        }

        // Track if ad loaded successfully for cleanup decision
        var loadSuccess = false

        try {
            // Set token info from source
            source.tokenInfo?.let { tokenInfo ->
                adSource.setTokenInfo(tokenInfo)
            }

            // Apply auction parameters
            AdSourceFactory.applyParams(
                adSource = adSource,
                auctionId = params.auctionId,
                auctionConfigurationId = params.auctionConfigurationId,
                auctionConfigurationUid = params.auctionConfigurationUid,
                externalWinNotificationsEnabled = params.externalWinNotificationsEnabled,
                demandAd = params.demandAd,
                pricefloor = params.pricefloor,
                adTypeParam = params.adTypeParam,
            )

            // Get auction params
            val adParams = adSource.getAuctionParam(
                org.bidon.sdk.adapter.AdAuctionParamSource(
                    activity = params.adTypeParam.activity,
                    pricefloor = params.pricefloor,
                    optBannerFormat = (params.adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (params.adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    adUnit = source.adUnit,
                )
            ).getOrNull()

            if (adParams == null) {
                logInfo(TAG, "RTB load failed: demandId=$demandId, failed to create auction params")
                if (source is RtbSource.FromCache) {
                    RtbPayloadCache.remove(demandId)
                }
                return Result.failure(BidonError.NoFill(DemandId(demandId)))
            }

            // Mark fill started (sets adUnit in stats for getAd() calls)
            adSource.markFillStarted(source.adUnit, params.pricefloor)

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
                    loadSuccess = true

                    // Update price to RTB eCPM
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.Successful,
                        price = ecpm
                    )

                    val auctionResult: AuctionResult = AuctionResult.Bidding(adSource, RoundStatus.Successful)

                    // Report successful RTB result to ResultsCollector
                    params.resultsCollector.add(auctionResult)

                    // Create CacheEntry — orchestrator will sort and cache after both pipelines complete
                    val cacheEntry: CacheEntry<AuctionResult> = CacheEntry.create(
                        value = auctionResult,
                        ecpm = ecpm,
                        demandId = demandId,
                        auctionId = params.auctionId,
                        uid = source.adUnit.uid
                    )

                    // Remove loaded source from cache if it was cached
                    if (source is RtbSource.FromCache) {
                        RtbPayloadCache.remove(demandId)
                    }

                    return Result.success(auctionResult to cacheEntry)
                }
                is AdEvent.LoadFailed, is AdEvent.Expired -> {
                    val error = when (adEvent) {
                        is AdEvent.LoadFailed -> adEvent.cause
                        is AdEvent.Expired -> BidonError.Expired(null)
                        else -> BidonError.NoFill(DemandId(demandId))
                    }
                    logInfo(TAG, "RTB load failed: demandId=$demandId, error=$error")
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = source.adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = source.tokenInfo
                    )
                    params.resultsCollector.add(failedResult)
                    if (source is RtbSource.FromCache) {
                        RtbPayloadCache.remove(demandId)
                    }
                }
                else -> {
                    logError(TAG, "Unexpected ad event: $adEvent", null)
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = source.adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = source.tokenInfo
                    )
                    params.resultsCollector.add(failedResult)
                    if (source is RtbSource.FromCache) {
                        RtbPayloadCache.remove(demandId)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logInfo(TAG, "RTB load failed: demandId=$demandId, exception=${e.message}")
            val roundStatus = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                RoundStatus.FillTimeoutReached
            } else {
                RoundStatus.NoFill
            }
            val failedResult = AuctionResult.AuctionFailed(
                adUnit = source.adUnit, roundStatus = roundStatus, tokenInfo = source.tokenInfo
            )
            params.resultsCollector.add(failedResult)
            if (source is RtbSource.FromCache) {
                RtbPayloadCache.remove(demandId)
            }
        } finally {
            if (!loadSuccess) {
                adSource.safeDestroy(demandId)
            }
        }

        return Result.failure(BidonError.NoFill(DemandId(demandId)))
    }

    /**
     * Cache untried new AdUnit sources for future auctions.
     *
     * @return Number of sources cached
     */
    private fun cacheUntriedSources(
        allSources: List<RtbSource>,
        triedDemandIds: Set<String>,
        auctionId: String,
    ): Int {
        var cachedCount = 0
        allSources.forEach { source ->
            if (source is RtbSource.FromAdUnit && source.demandId !in triedDemandIds) {
                val payload = RtbPayload(
                    adUnit = source.adUnit,
                    tokenInfo = source.tokenInfo,
                    auctionId = auctionId
                )
                val inserted = RtbPayloadCache.putIfHigherEcpm(payload)
                if (inserted) {
                    cachedCount++
                }
            }
        }
        if (cachedCount > 0) {
            logInfo(TAG, "RTB: cached $cachedCount new payloads for future auctions")
        }
        return cachedCount
    }
}

private const val TAG = "[DenisCache] RTB"
