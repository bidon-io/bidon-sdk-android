package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Entry point for auction orchestration and cold/warm start coordination.
 *
 * Determines auction strategy based on cache state:
 * 1. Warm start: READY_TO_SHOW cache not empty → immediate callback (<1s)
 * 2. Cold start with cache: RTB_PAYLOAD cache has entries → skip tokens for cached adapters
 * 3. Pure cold start: Both caches empty → full token collection with user pricefloor
 *
 * Core responsibilities:
 * - Capture cache state at auction start (no re-validation during processing)
 * - Determine warm vs cold start path
 * - Calculate dynamic pricefloor with safety margin
 * - Provide state to parallel processors (Phase 2)
 *
 * Thread-safety: Reads from singleton caches (thread-safe via ConcurrentHashMap).
 * Warm start path is synchronous (no async operations for <1s callback requirement).
 */
internal class CoordinationLayer {
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
                        "Warm start: cached ad available (demandId=${bestAd.demandId}, ecpm=${bestAd.ecpm})"
                    )
                    AuctionStartState.WarmStart(bestAd)
                } else {
                    // Edge case: isEmpty() returned false but getBest() null (race condition)
                    // Between isEmpty() check and getBest() call, another thread may have
                    // removed/expired the last entry. Fall back to cold start.
                    logInfo(TAG, "Warning: cache reported non-empty but getBest() returned null (race condition)")
                    AuctionStartState.PureColdStart(userPricefloor)
                }
            }
            !snapshot.rtbPayloadIsEmpty -> {
                // Cold start with RTB cache optimization
                logInfo(
                    TAG,
                    "Cold start with cache: ${snapshot.cachedDemandIds.size} RTB payloads cached " +
                        "(maxEcpm=${snapshot.rtbPayloadMaxEcpm})"
                )
                AuctionStartState.ColdStartWithCache(
                    cachedDemandIds = snapshot.cachedDemandIds,
                    maxCachedEcpm = snapshot.rtbPayloadMaxEcpm
                )
            }
            else -> {
                // Pure cold start
                logInfo(TAG, "Pure cold start: both caches empty (userPricefloor=$userPricefloor)")
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
        return PricefloorCalculator.calculateDynamicPricefloor(
            userPricefloor = userPricefloor,
            readyToShowMaxEcpm = snapshot.readyToShowMaxEcpm,
            rtbPayloadMaxEcpm = snapshot.rtbPayloadMaxEcpm
        )
    }

    companion object {
        private const val TAG = "CoordinationLayer"
    }
}
