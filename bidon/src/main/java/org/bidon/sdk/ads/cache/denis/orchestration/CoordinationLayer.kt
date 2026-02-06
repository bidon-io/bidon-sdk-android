package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.ads.cache.denis.usecases.GetTokensWithSkipUseCase
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Entry point for auction orchestration and cold/warm start coordination.
 *
 * Determines auction strategy based on cache state:
 * 1. Warm start: READY_TO_SHOW cache not empty → immediate callback (<1s) + background auction
 * 2. Cold start with cache: RTB_PAYLOAD cache has entries → skip tokens for cached adapters
 * 3. Pure cold start: Both caches empty → full token collection with user pricefloor
 *
 * Core responsibilities:
 * - Capture cache state at auction start (no re-validation during processing)
 * - Determine warm vs cold start path
 * - Calculate dynamic pricefloor with safety margin
 * - Provide state to parallel processors (Phase 2)
 *
 * CRITICAL: Both warm and cold start paths launch background auctions.
 * - Warm start: onAdLoaded fires IMMEDIATELY, auction continues in background to replenish cache
 * - Cold start: onAdLoaded fires when FIRST ad loads, auction continues for remaining ads
 *
 * Thread-safety: Reads from singleton caches (thread-safe via ConcurrentHashMap).
 * Warm start callback is synchronous (no async operations for <1s callback requirement).
 */
internal class CoordinationLayer(
    private val adaptersSource: AdaptersSource,
    private val getTokensWithSkip: GetTokensWithSkipUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor,
    private val lifecycleManager: LifecycleManager,
) {
    /**
     * Determine auction start state based on cache contents.
     *
     * Decision logic (from 03-CONTEXT.md):
     * 1. If READY_TO_SHOW cache not empty → WarmStart (immediate callback)
     * 2. If RTB_PAYLOAD cache not empty → ColdStartWithCache (skip tokens for cached)
     * 3. Otherwise → PureColdStart (full auction)
     *
     * Cache state captured ONCE at call time (no re-validation during processing).
     * User decision: "Cache state changes during processing are acceptable" - trust
     * snapshot for entire auction lifecycle.
     *
     * Handles edge case: Cache isEmpty() returns false but getBest() returns null
     * (race condition). Falls back to PureColdStart to maintain correctness.
     *
     * @param userPricefloor Publisher-configured minimum eCPM
     * @return Pair of (auction state, cache snapshot) for pricefloor calculation
     */
    fun determineStartState(userPricefloor: Double): Pair<AuctionStartState, CacheStateSnapshot> {
        // Capture cache state BEFORE any async operations
        val snapshot = CacheStateSnapshot.capture()

        val state = when {
            !snapshot.readyToShowIsEmpty -> {
                // Warm start: serve cached ad immediately
                val bestAd = ReadyToShowCache.getBest()
                if (bestAd != null) {
                    logInfo(
                        TAG,
                        "WARM START: Serving ${bestAd.demandId} @ ${"$%.2f".format(bestAd.ecpm)}"
                    )
                    AuctionStartState.WarmStart(bestAd)
                } else {
                    // Edge case: Race condition between isEmpty() and getBest()
                    // Fall back to cold start
                    AuctionStartState.PureColdStart(userPricefloor)
                }
            }
            !snapshot.rtbPayloadIsEmpty -> {
                // Cold start with RTB cache optimization
                logInfo(
                    TAG,
                    "❄️ COLD START WITH CACHE: ${snapshot.cachedDemandIds.size} RTB payloads available " +
                        "(max eCPM: $${"%.2f".format(snapshot.rtbPayloadMaxEcpm)}), will merge with server response"
                )
                AuctionStartState.ColdStartWithCache(
                    cachedDemandIds = snapshot.cachedDemandIds,
                    maxCachedEcpm = snapshot.rtbPayloadMaxEcpm
                )
            }
            else -> {
                // Pure cold start
                logInfo(TAG, "COLD START: Full auction, pricefloor=${"$%.2f".format(userPricefloor)}")
                AuctionStartState.PureColdStart(userPricefloor)
            }
        }

        return state to snapshot
    }

    /**
     * Calculate dynamic pricefloor for auction request.
     *
     * Uses cached eCPM values with 0.9 safety margin to protect cached ad value
     * while allowing slightly better bids to compete.
     *
     * Formula: max(userPricefloor, 0.9 * max(READY_TO_SHOW, RTB_PAYLOAD))
     *
     * Called once at auction start, result used for entire auction lifecycle.
     * No recalculation during processing (maintains consistency).
     *
     * @param userPricefloor Publisher-configured minimum eCPM
     * @param snapshot Cache state snapshot from determineStartState()
     * @return Calculated pricefloor for auction request
     */
    fun calculatePricefloor(userPricefloor: Double, snapshot: CacheStateSnapshot): Double {
        val dynamicPricefloor = PricefloorCalculator.calculateDynamicPricefloor(
            userPricefloor = userPricefloor,
            readyToShowMaxEcpm = snapshot.readyToShowMaxEcpm,
            rtbPayloadMaxEcpm = snapshot.rtbPayloadMaxEcpm
        )

        logInfo(
            TAG,
            "PRICEFLOOR: user=${"$%.2f".format(userPricefloor)} → dynamic=${"$%.2f".format(dynamicPricefloor)}"
        )

        return dynamicPricefloor
    }

    /**
     * Orchestrate complete auction flow.
     *
     * @return AuctionCompletionType indicating how auction completed:
     *   - WarmStartServed: cached ad served immediately, background auction started to replenish cache
     *   - ColdStartInProgress: cold start auction ongoing, results via callbacks
     */
    suspend fun coordinateAuction(
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        tokenTimeout: Long,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, BidonError) -> Unit,
    ): AuctionCompletionType {
        // Start lifecycle management (idempotent, safe to call multiple times)
        lifecycleManager.start()

        val userPricefloor = adTypeParam.pricefloor
        val (startState, snapshot) = determineStartState(userPricefloor)

        return when (startState) {
            is AuctionStartState.WarmStart -> {
                // 1. Serve cached ad immediately (synchronous callback)
                handleWarmStart(startState.bestAd, onSuccess)

                // 2. Launch background auction to replenish cache
                val auctionId = java.util.UUID.randomUUID().toString()
                val job = lifecycleManager.getScope().launch {
                    handleColdStart(
                        auctionId = auctionId,
                        skipDemandIds = snapshot.cachedDemandIds, // Use RTB_PAYLOAD cache optimization
                        snapshot = snapshot,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        tokenTimeout = tokenTimeout,
                        onSuccess = { _, _ -> /* no-op: onSuccess already fired in handleWarmStart */ },
                        onFailure = { _, _ -> /* no-op: already returned success, silent cache refresh */ },
                        silentMode = true, // Don't trigger callbacks, just update cache
                    )
                }
                lifecycleManager.registerAuction(auctionId, job)
                AuctionCompletionType.WarmStartServed // Callback already fired, auction in background
            }
            is AuctionStartState.ColdStartWithCache -> {
                // Launch cold start on lifecycle-managed scope and register job
                val auctionId = java.util.UUID.randomUUID().toString()
                val job = lifecycleManager.getScope().launch {
                    handleColdStart(
                        auctionId = auctionId,
                        skipDemandIds = startState.cachedDemandIds,
                        snapshot = snapshot,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        tokenTimeout = tokenTimeout,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                        silentMode = false,
                    )
                }
                lifecycleManager.registerAuction(auctionId, job)
                AuctionCompletionType.ColdStartInProgress
            }
            is AuctionStartState.PureColdStart -> {
                // Launch cold start on lifecycle-managed scope and register job
                val auctionId = java.util.UUID.randomUUID().toString()
                val job = lifecycleManager.getScope().launch {
                    handleColdStart(
                        auctionId = auctionId,
                        skipDemandIds = emptySet(),
                        snapshot = snapshot,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        tokenTimeout = tokenTimeout,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                        silentMode = false,
                    )
                }
                lifecycleManager.registerAuction(auctionId, job)
                AuctionCompletionType.ColdStartInProgress
            }
        }
    }

    /**
     * Handle warm start: serve cached ad immediately.
     *
     * Fires onSuccess callback with best ad from cache.
     * No auction is started - cached ad served directly.
     */
    private fun handleWarmStart(
        bestAd: CacheEntry<AuctionResult>,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit
    ) {
        logInfo(TAG, "WARM START: Serving ${bestAd.demandId} @ ${"$%.2f".format(bestAd.ecpm)}")

        // Build AuctionInfo from cached entry
        // Note: Warm start uses cached auctionId from when ad was originally loaded
        val auctionResult = bestAd.value
        val auctionInfo = AuctionInfo(
            auctionId = bestAd.auctionId,
            auctionConfigurationId = null, // Not stored in cache entry
            auctionConfigurationUid = null, // Not stored in cache entry
            auctionTimeout = 0L, // Not relevant for cached ad
            auctionPricefloor = bestAd.ecpm, // Use cached eCPM
            noBids = null,
            adUnits = null,
        )

        // Fire callback immediately
        onSuccess(auctionResult, auctionInfo)
    }

    /**
     * Handle cold start: token collection, auction request, waterfall splitting, parallel processing.
     *
     * CRITICAL PRICEFLOOR WIRING:
     * The dynamic pricefloor must be passed to the auction request.
     * Since AdTypeParam is sealed (cannot be copied with modified pricefloor),
     * we create a modified version that replaces the pricefloor.
     *
     * @param silentMode If true, suppress callbacks (used for warm start background refresh).
     *                   Auction continues to update cache but doesn't fire onSuccess/onFailure.
     */
    private suspend fun handleColdStart(
        auctionId: String,
        skipDemandIds: Set<String>,
        snapshot: CacheStateSnapshot,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        tokenTimeout: Long,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, BidonError) -> Unit,
        silentMode: Boolean = false,
    ) {
        try {
            val dynamicPricefloor = calculatePricefloor(adTypeParam.pricefloor, snapshot)
            logInfo(TAG, "COLD START: pricefloor=${"$%.2f".format(dynamicPricefloor)} (from user=${"$%.2f".format(adTypeParam.pricefloor)}), skip=${skipDemandIds.size}")

            // Create adTypeParam with dynamic pricefloor for auction request
            // This ensures the backend receives the dynamic pricefloor, not the original
            val adTypeParamWithDynamicPricefloor = adTypeParam.withPricefloor(dynamicPricefloor)

            // Step 1: Collect tokens (with skip optimization)
            val tokens = getTokensWithSkip(
                adTypeParam = adTypeParam, // Original for token collection
                adaptersSource = adaptersSource,
                tokenTimeout = tokenTimeout,
                skipDemandIds = skipDemandIds,
            )

            // Step 2: Request auction WITH DYNAMIC PRICEFLOOR
            val auctionResponse = getAuctionRequest.request(
                adTypeParam = adTypeParamWithDynamicPricefloor, // <-- Dynamic pricefloor used here
                auctionId = auctionId,
                demandAd = demandAd,
                adapters = adaptersSource.adapters.associate {
                    it.demandId.demandId to it.adapterInfo
                },
                tokens = tokens,
            )

            // Step 3: Handle auction response - split waterfall and execute parallel auction
            auctionResponse.fold(
                onSuccess = { response ->
                    // Step 3a: Split waterfall into RTB and CPM groups using WaterfallSplitter
                    val adUnits = response.adUnits ?: emptyList()
                    val splitWaterfall = WaterfallSplitter.split(
                        adUnits = adUnits,
                        adaptersSource = adaptersSource
                    )

                    // Step 3b: Merge server RTB with cached RTB payloads
                    val mergedRtbAdUnits = mergeAndSortRtb(
                        serverRtbAdUnits = splitWaterfall.rtbAdUnits,
                        auctionId = auctionId
                    )

                    logInfo(
                        TAG,
                        "Waterfall split complete: rtb=${splitWaterfall.rtbAdUnits.size} (server) + " +
                            "${mergedRtbAdUnits.size - splitWaterfall.rtbAdUnits.size} (cached) = ${mergedRtbAdUnits.size} total, " +
                            "cpm=${splitWaterfall.cpmAdUnits.size}"
                    )

                    // Detailed waterfall logging
                    if (mergedRtbAdUnits.isNotEmpty()) {
                        val serverCount = splitWaterfall.rtbAdUnits.size
                        val cachedCount = mergedRtbAdUnits.size - serverCount
                        val rtbDetails = mergedRtbAdUnits.take(5).joinToString(", ") {
                            "${it.demandId}:${"$%.2f".format(it.pricefloor)}"
                        }
                        val more = if (mergedRtbAdUnits.size > 5) " +${mergedRtbAdUnits.size - 5} more" else ""
                        logInfo(
                            TAG,
                            "RTB WATERFALL: $serverCount server + $cachedCount cached = ${mergedRtbAdUnits.size} total [$rtbDetails$more]"
                        )
                    }
                    if (splitWaterfall.cpmAdUnits.isNotEmpty()) {
                        val cpmDetails = splitWaterfall.cpmAdUnits.take(5).joinToString(", ") { adUnit ->
                            val weight = org.bidon.sdk.ads.cache.denis.processors.WeightModel.getWeight(adUnit.demandId)
                            val score = org.bidon.sdk.ads.cache.denis.processors.WeightModel.calculateScore(adUnit)
                            "${adUnit.demandId}:${"$%.2f".format(adUnit.pricefloor)}(w$weight/s${"%.2f".format(score)})"
                        }
                        val more = if (splitWaterfall.cpmAdUnits.size > 5) " +${splitWaterfall.cpmAdUnits.size - 5} more" else ""
                        logInfo(
                            TAG,
                            "CPM WATERFALL: ${splitWaterfall.cpmAdUnits.size} networks (weighted) [$cpmDetails$more]"
                        )
                    }

                    // Build AuctionInfo for callbacks
                    val auctionInfo = AuctionInfo(
                        auctionId = auctionId,
                        auctionConfigurationId = response.auctionConfigurationId,
                        auctionConfigurationUid = response.auctionConfigurationUid,
                        auctionTimeout = response.auctionTimeout,
                        auctionPricefloor = response.pricefloor,
                        noBids = null,
                        adUnits = null,
                    )

                    // Step 3b: Create per-auction orchestrator with ACTUAL callbacks
                    // In silent mode (warm start background refresh), suppress callbacks
                    val callbackCoordinator = CallbackCoordinator(
                        onAdLoaded = if (silentMode) {
                            { _, _ -> /* Silent mode: no callback */ }
                        } else {
                            onSuccess
                        },
                        onAdLoadFailed = if (silentMode) {
                            { _, _ -> /* Silent mode: no callback */ }
                        } else {
                            { info, error -> onFailure(info, error) }
                        },
                    )
                    val orchestrator = ParallelAuctionOrchestrator(
                        rtbProcessor = rtbProcessor,
                        cpmProcessor = cpmProcessor,
                        callbackCoordinator = callbackCoordinator,
                    )

                    // Execute parallel auction via per-auction orchestrator
                    orchestrator.executeParallelAuction(
                        rtbAdUnits = mergedRtbAdUnits, // RTB group: server + cached, sorted by eCPM
                        cpmAdUnits = splitWaterfall.cpmAdUnits, // CPM group from split
                        adTypeParam = adTypeParamWithDynamicPricefloor, // Use dynamic pricefloor
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = response.auctionConfigurationId ?: 0L,
                        auctionConfigurationUid = response.auctionConfigurationUid ?: "",
                        externalWinNotificationsEnabled = response.externalWinNotificationsEnabled,
                        pricefloor = dynamicPricefloor,
                        auctionInfo = auctionInfo,
                    )
                },
                onFailure = { error ->
                    // Auction request failed - notify via failure callback (unless silent mode)
                    logInfo(TAG, "Auction request failed: ${error.message}")
                    if (!silentMode) {
                        onFailure(null, BidonError.InternalServerSdkError(error.message ?: "Auction request failed"))
                    }
                }
            )
        } finally {
            // Clear auction state after completion (success or failure)
            lifecycleManager.onAuctionCompleted(auctionId)
        }
    }

    /**
     * Merge server RTB ad units with cached RTB payloads and sort by eCPM.
     *
     * Strategy:
     * 1. Collect RTB from server response
     * 2. Collect RTB from RTB_PAYLOAD cache
     * 3. Deduplicate by demandId (keep highest eCPM)
     * 4. Sort by eCPM descending (highest first)
     *
     * This ensures we use the best available RTB payloads from both sources.
     *
     * @param serverRtbAdUnits RTB ad units from auction response
     * @param auctionId Current auction ID for logging
     * @return Merged and sorted RTB ad units
     */
    private fun mergeAndSortRtb(
        serverRtbAdUnits: List<AdUnit>,
        auctionId: String
    ): List<AdUnit> {
        // Get cached RTB payloads (already sorted by eCPM)
        val cachedRtbEntries = RtbPayloadCache.getAllSortedByEcpm()
        val cachedRtbAdUnits = cachedRtbEntries.map { it.value.adUnit }

        logInfo(
            TAG,
            "Merging RTB: server=${serverRtbAdUnits.size}, cached=${cachedRtbAdUnits.size}"
        )

        // Combine both sources
        val allRtbAdUnits = serverRtbAdUnits + cachedRtbAdUnits

        // Deduplicate by demandId, keeping highest eCPM
        val deduplicatedRtb = allRtbAdUnits
            .groupBy { it.demandId }
            .mapNotNull { (demandId, adUnits) ->
                // Keep the one with highest pricefloor (eCPM)
                adUnits.maxByOrNull { it.pricefloor } ?: run {
                    logInfo(TAG, "Warning: empty adUnits group for demandId=$demandId (should not happen)")
                    null
                }
            }

        // Sort by eCPM descending (highest first)
        val sortedRtb = deduplicatedRtb.sortedByDescending { it.pricefloor }

        if (sortedRtb.isNotEmpty()) {
            val topEcpms = sortedRtb.take(3).joinToString { "${it.demandId}:$${it.pricefloor}" }
            logInfo(
                TAG,
                "Merged RTB: ${sortedRtb.size} ad units (deduped), top 3 by eCPM: [$topEcpms]"
            )
        }

        return sortedRtb
    }

    companion object {
        private const val TAG = "[DenisCache] Coordination"
    }
}

/**
 * Extension function to create AdTypeParam with modified pricefloor.
 *
 * Since AdTypeParam is sealed (cannot be copied with modified pricefloor),
 * we recreate the specific subtype with the new pricefloor value.
 *
 * This is a file-private extension function in CoordinationLayer.kt.
 */
private fun AdTypeParam.withPricefloor(pricefloor: Double): AdTypeParam = when (this) {
    is AdTypeParam.Banner -> AdTypeParam.Banner(
        activity = activity,
        pricefloor = pricefloor,
        auctionKey = auctionKey,
        bannerFormat = bannerFormat,
        containerWidth = containerWidth,
    )
    is AdTypeParam.Interstitial -> AdTypeParam.Interstitial(
        activity = activity,
        pricefloor = pricefloor,
        auctionKey = auctionKey,
    )
    is AdTypeParam.Rewarded -> AdTypeParam.Rewarded(
        activity = activity,
        pricefloor = pricefloor,
        auctionKey = auctionKey,
    )
}
