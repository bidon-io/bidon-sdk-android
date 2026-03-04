package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.denis.lifecycle.CancellationManager
import org.bidon.sdk.ads.cache.denis.processors.AuctionParams
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.di.get
import kotlin.math.max

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
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor,
    private val scope: CoroutineScope,
    private val cancellationManager: CancellationManager,
    private val auctionStat: AuctionStat,
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
     * Handles edge case: Cache isEmpty() returns false but peekFirst() returns null
     * (race condition). Falls back to PureColdStart to maintain correctness.
     *
     * @param userPricefloor Publisher-configured minimum eCPM
     * @return Pair of (auction state, cache snapshot) for pricefloor calculation
     */
    private fun determineStartState(userPricefloor: Double): Pair<AuctionStartState, CacheStateSnapshot> {
        // Capture cache state BEFORE any async operations
        val snapshot = CacheStateSnapshot.capture()

        val state = when {
            !snapshot.readyToShowIsEmpty -> {
                // Warm start: serve cached ad immediately
                val bestAd = ReadyToShowCache.peekFirst()
                if (bestAd != null) {
                    logInfo(
                        TAG,
                        "WARM START: Serving ${bestAd.demandId} @ ${"$%.2f".format(bestAd.ecpm)}"
                    )
                    AuctionStartState.WarmStart(bestAd)
                } else {
                    // Edge case: Race condition between isEmpty() and peekFirst()
                    // Fall back to cold start
                    AuctionStartState.PureColdStart(userPricefloor)
                }
            }
            !snapshot.rtbPayloadIsEmpty -> {
                // Cold start with RTB cache optimization
                logInfo(TAG, "COLD START WITH CACHE: ${snapshot.cachedDemandIds.size} RTB payloads cached")
                AuctionStartState.ColdStartWithCache(
                    cachedDemandIds = snapshot.cachedDemandIds,
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
     * Orchestrate complete auction flow.
     *
     * Handles warm start (cached ad served immediately + background auction)
     * and cold start (full auction, results via callbacks).
     */
    suspend fun coordinateAuction(
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        tokenTimeout: Long,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, BidonError) -> Unit,
    ) {
        // Note: PeriodicSweepJob.start() is idempotent, called externally before coordinateAuction

        val userPricefloor = adTypeParam.pricefloor
        val (startState, snapshot) = determineStartState(userPricefloor)

        when (startState) {
            is AuctionStartState.WarmStart -> {
                // 1. Serve cached ad immediately (synchronous callback)
                handleWarmStart(startState.bestAd, onSuccess)

                // 2. Launch background auction to replenish cache (no callbacks — warm start is final)
                launchColdStart(
                    skipDemandIds = snapshot.cachedDemandIds, // Use RTB_PAYLOAD cache optimization
                    adTypeParam = adTypeParam,
                    demandAd = demandAd,
                    tokenTimeout = tokenTimeout,
                    onSuccess = { _, _ -> },
                    onFailure = { _, _ -> },
                )
            }
            is AuctionStartState.ColdStartWithCache -> {
                launchColdStart(
                    skipDemandIds = startState.cachedDemandIds,
                    adTypeParam = adTypeParam,
                    demandAd = demandAd,
                    tokenTimeout = tokenTimeout,
                    onSuccess = onSuccess,
                    onFailure = onFailure,
                )
            }
            is AuctionStartState.PureColdStart -> {
                launchColdStart(
                    skipDemandIds = emptySet(),
                    adTypeParam = adTypeParam,
                    demandAd = demandAd,
                    tokenTimeout = tokenTimeout,
                    onSuccess = onSuccess,
                    onFailure = onFailure,
                )
            }
        }
    }

    /**
     * Launch cold start auction in background with lifecycle management.
     *
     * Encapsulates the pattern of:
     * 1. Generate unique auctionId
     * 2. Launch coroutine on lifecycle-managed scope
     * 3. Register auction job for cancellation tracking
     *
     * @param skipDemandIds Demand IDs to skip in token collection (from RTB cache)
     * @param adTypeParam Ad type parameters including pricefloor
     * @param demandAd Ad instance configuration
     * @param tokenTimeout Timeout for token collection
     * @param onSuccess Callback for successful auction
     * @param onFailure Callback for failed auction
     */
    private fun launchColdStart(
        skipDemandIds: Set<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        tokenTimeout: Long,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, BidonError) -> Unit,
    ) {
        val auctionId = java.util.UUID.randomUUID().toString()
        val job = scope.launch {
            handleColdStart(
                auctionId = auctionId,
                skipDemandIds = skipDemandIds,
                adTypeParam = adTypeParam,
                demandAd = demandAd,
                tokenTimeout = tokenTimeout,
                onSuccess = onSuccess,
                onFailure = onFailure,
            )
        }
        cancellationManager.registerAuction(auctionId, job)
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
        // Build AuctionInfo from cached entry
        // Note: Warm start uses cached auctionId from when ad was originally loaded
        val auctionResult = bestAd.value
        val stats = auctionResult.adSource.getStats()
        val winnerAdUnitInfo = AdUnitInfo(
            demandId = stats.demandId.demandId,
            label = stats.adUnit?.label,
            price = stats.price,
            uid = stats.adUnit?.uid,
            bidType = stats.bidType?.code,
            fillStartTs = stats.fillStartTs,
            fillFinishTs = stats.fillFinishTs,
            status = RoundStatus.Win.code,
            ext = stats.adUnit?.extra?.toString(),
        )
        val auctionInfo = AuctionInfo(
            auctionId = bestAd.auctionId,
            auctionConfigurationId = null, // Not stored in cache entry
            auctionConfigurationUid = null, // Not stored in cache entry
            auctionTimeout = 0L, // Not relevant for cached ad
            auctionPricefloor = bestAd.ecpm, // Use cached eCPM
            noBids = null, // Not relevant for warm start
            adUnits = listOf(winnerAdUnitInfo),
        )

        // Fire callback immediately
        onSuccess(auctionResult, auctionInfo)
    }

    /**
     * Handle cold start: token collection, auction request, waterfall splitting, parallel processing.
     *
     * Uses the publisher's pricefloor (from adTypeParam.pricefloor) directly — no dynamic calculation.
     */
    private suspend fun handleColdStart(
        auctionId: String,
        skipDemandIds: Set<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        tokenTimeout: Long,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, BidonError) -> Unit,
    ) {
        // Create fresh ResultsCollector for this auction
        val resultsCollector: ResultsCollector = get()
        val pricefloor = adTypeParam.pricefloor

        try {
            // Initialize ResultsCollector lifecycle
            resultsCollector.startRound(pricefloor)
            resultsCollector.serverBiddingStarted()

            // Mark auction started for stats tracking (mirrors AuctionImpl pattern)
            auctionStat.markAuctionStarted(auctionId, adTypeParam)

            // Step 1: Collect tokens (with skip optimization)
            val tokens = collectTokens(
                adTypeParam = adTypeParam,
                tokenTimeout = tokenTimeout,
                skipDemandIds = skipDemandIds,
            )

            // Step 2: Request auction with publisher's pricefloor
            val auctionResponse = getAuctionRequest.request(
                adTypeParam = adTypeParam,
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
                    // Report server bidding finished to ResultsCollector
                    resultsCollector.serverBiddingFinished(
                        response.adUnits?.filter { it.bidType == BidType.RTB }
                    )
                    resultsCollector.setNoBidInfo(response.noBids)

                    // Step 3a: Split waterfall into RTB and CPM groups
                    val adUnits = response.adUnits ?: emptyList()
                    val biddingDemandIds = adaptersSource.adapters
                        .filterIsInstance<org.bidon.sdk.adapter.Adapter.Bidding>()
                        .map { it.demandId.demandId }
                        .toSet()
                    val (rtbAdUnits, cpmAdUnits) = adUnits.partition { it.demandId in biddingDemandIds }

                    logInfo(TAG, "Waterfall: ${rtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM")

                    // Reduce auction timeout for faster cache auctions
                    val effectiveTimeout = max(
                        response.auctionTimeout - AUCTION_TIMEOUT_REDUCTION_MS,
                        MIN_AUCTION_TIMEOUT_MS
                    )

                    val orchestrator = ParallelAuctionOrchestrator(
                        rtbProcessor = rtbProcessor,
                        cpmProcessor = cpmProcessor,
                    )

                    // Build common auction parameters for processors
                    val auctionParams = AuctionParams(
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = response.auctionConfigurationId ?: 0L,
                        auctionConfigurationUid = response.auctionConfigurationUid ?: "",
                        externalWinNotificationsEnabled = response.externalWinNotificationsEnabled,
                        pricefloor = pricefloor,
                        resultsCollector = resultsCollector,
                    )

                    // Execute parallel auction via per-auction orchestrator with timeout
                    try {
                        withTimeout(effectiveTimeout) {
                            orchestrator.executeParallelAuction(
                                rtbAdUnits = rtbAdUnits,
                                cpmAdUnits = cpmAdUnits,
                                params = auctionParams,
                            )
                        }
                    } catch (_: TimeoutCancellationException) {
                        logInfo(TAG, "Auction timed out after ${effectiveTimeout}ms, using available results")
                    }

                    // Cache path: do NOT call saveWinners() — it marks non-winners as LOSE
                    // and destroys their ad sources, but cached ads stay alive in ReadyToShowCache.

                    // Collect round results AFTER fill completes (mirrors AuctionImpl pattern)
                    val roundStat = proceedRoundResults(resultsCollector)

                    // Build AuctionInfo AFTER fill — so adUnits have status, fillStartTs, fillFinishTs
                    // (mirrors AuctionImpl.getAuctionInfo)
                    val auctionInfo = AuctionInfo(
                        auctionId = auctionId,
                        auctionConfigurationId = response.auctionConfigurationId,
                        auctionConfigurationUid = response.auctionConfigurationUid,
                        auctionTimeout = effectiveTimeout,
                        auctionPricefloor = response.pricefloor,
                        noBids = roundStat?.noBids?.map { it.toAuctionNoBidInfo() },
                        adUnits = roundStat?.demands?.map { it.toAuctionInfo() },
                    )

                    // Fire callback based on cache state
                    val bestEntry = ReadyToShowCache.peekFirst()
                    if (bestEntry != null) {
                        onSuccess(bestEntry.value, auctionInfo)
                    } else {
                        onFailure(auctionInfo, BidonError.NoFill(DemandId("auction")))
                    }

                    auctionStat.sendAuctionStats(
                        auctionData = response,
                        roundStat = roundStat,
                        demandAd = demandAd,
                    )
                },
                onFailure = { error ->
                    // Auction request failed - no AuctionResponse available, skip stats
                    // (mirrors AuctionImpl.processAuctionFailed: only sends stats when auctionData exists)
                    logInfo(TAG, "Auction request failed: ${error.message}")
                    onFailure(null, BidonError.InternalServerSdkError(error.message ?: "Auction request failed"))
                }
            )
        } finally {
            // Clear auction state after completion (success or failure)
            cancellationManager.onAuctionCompleted(auctionId)
        }
    }

    /**
     * Extract round results from ResultsCollector and add to AuctionStat.
     * Mirrors AuctionImpl.proceedRoundResults() pattern.
     */
    private suspend fun proceedRoundResults(resultsCollector: ResultsCollector): RoundStat? {
        (resultsCollector.getRoundResults() as? RoundResult.Results)?.let {
            return auctionStat.addRoundResults(it)
        }
        return null
    }

    /**
     * Collect tokens with skip optimization for cached RTB adapters.
     * BidMachine is always included (requires tokens even when cached).
     */
    private suspend fun collectTokens(
        adTypeParam: AdTypeParam,
        tokenTimeout: Long,
        skipDemandIds: Set<String>,
    ): Map<String, org.bidon.sdk.auction.models.TokenInfo> {
        if (skipDemandIds.isEmpty()) {
            return getTokens(adTypeParam, adaptersSource, tokenTimeout)
        }

        // Always collect tokens for BidMachine, even when payload is cached
        val effectiveSkipDemandIds = skipDemandIds - BIDMACHINE_DEMAND_ID

        logInfo(
            TAG,
            "Token collection: skipping ${effectiveSkipDemandIds.size} of " +
                "${adaptersSource.adapters.count { it is org.bidon.sdk.adapter.Adapter.Bidding }} " +
                "bidding adapters (cached RTB payloads)"
        )

        val filteredAdaptersSource = object : AdaptersSource {
            override val adapters: Set<org.bidon.sdk.adapter.Adapter>
                get() = adaptersSource.adapters.filter { adapter ->
                    adapter.demandId.demandId !in effectiveSkipDemandIds
                }.toSet()

            override fun add(adapter: org.bidon.sdk.adapter.Adapter) {
                adaptersSource.add(adapter)
            }
        }

        return getTokens(adTypeParam, filteredAdaptersSource, tokenTimeout)
    }

    companion object {
        private const val TAG = "[DenisCache] Coordination"

        // Reduce auction timeout for faster response when RTB is available in cache
        private const val AUCTION_TIMEOUT_REDUCTION_MS = 5_000L
        private const val MIN_AUCTION_TIMEOUT_MS = 5_000L
        private const val BIDMACHINE_DEMAND_ID = "bidmachine"
    }
}

/**
 * Immutable snapshot of cache state at auction start.
 */
private data class CacheStateSnapshot(
    val readyToShowIsEmpty: Boolean,
    val rtbPayloadIsEmpty: Boolean,
    val rtbPayloadMaxEcpm: Double,
    val cachedDemandIds: Set<String>,
) {
    companion object {
        fun capture(): CacheStateSnapshot = CacheStateSnapshot(
            readyToShowIsEmpty = ReadyToShowCache.isEmpty(),
            rtbPayloadIsEmpty = RtbPayloadCache.isEmpty(),
            rtbPayloadMaxEcpm = RtbPayloadCache.getMaxEcpm(),
            cachedDemandIds = RtbPayloadCache.getCachedDemandIds()
        )
    }
}
