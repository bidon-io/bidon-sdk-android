package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Callback coordinator for exactly-once callback semantics in parallel auctions.
 *
 * Ensures that:
 * - onAdLoaded fires exactly ONCE (first successful ad load)
 * - onAdLoadFailed fires ONLY if cache was empty AND both RTB+CPM failed
 * - Thread-safe using AtomicBoolean (lock-free, no contention)
 *
 * Usage:
 * 1. setCacheEmptyAtStart(isEmpty) before starting auction
 * 2. notifySuccess() when first ad loads (only first call fires callback)
 * 3. notifyFailure() when both branches fail (checks cache state)
 * 4. reset() when starting new auction
 *
 * Thread-safety: AtomicBoolean ensures lock-free concurrent access.
 * No deadlocks possible (no mutexes, just atomic CAS operations).
 */
internal class CallbackCoordinator(
    private val onAdLoaded: (AuctionResult, AuctionInfo) -> Unit,
    private val onAdLoadFailed: (AuctionInfo?, BidonError) -> Unit,
) {
    private val loadedCallbackFired = AtomicBoolean(false)
    private val failedCallbackFired = AtomicBoolean(false)
    private var cacheWasEmptyAtStart = true // Set before auction starts

    /**
     * Record cache state before auction starts.
     *
     * Used to determine if failure callback should fire.
     * If cache was NOT empty, we don't fire failure callback
     * (user can still show cached ad).
     *
     * @param isEmpty Cache empty state before auction
     */
    fun setCacheEmptyAtStart(isEmpty: Boolean) {
        cacheWasEmptyAtStart = isEmpty
    }

    /**
     * Notify success (first ad loaded).
     *
     * Fires onAdLoaded callback exactly ONCE (atomic compare-and-set).
     * Subsequent calls are no-ops.
     *
     * @param result Successfully loaded auction result
     * @param auctionInfo Auction information for callback
     */
    fun notifySuccess(result: AuctionResult, auctionInfo: AuctionInfo) {
        // Atomic compare-and-set: only first call returns true
        if (loadedCallbackFired.compareAndSet(false, true)) {
            logInfo(TAG, "Firing onAdLoaded callback")
            onAdLoaded(result, auctionInfo)
        }
    }

    /**
     * Notify failure (both RTB and CPM failed).
     *
     * Fires onAdLoadFailed callback ONLY if:
     * 1. Success callback hasn't fired (no ad loaded)
     * 2. Cache was empty at start (otherwise there's still cached ad to show)
     * 3. Failure callback hasn't fired yet (atomic check)
     *
     * This ensures we only fire failure when user has NO ad to show.
     *
     * @param auctionInfo Auction information for callback (nullable)
     * @param error Error that caused failure
     */
    fun notifyFailure(auctionInfo: AuctionInfo?, error: BidonError) {
        // Only fire if:
        // - Success callback hasn't fired
        // - Cache was empty at start
        // - Failure callback hasn't fired yet
        if (!loadedCallbackFired.get() &&
            cacheWasEmptyAtStart &&
            failedCallbackFired.compareAndSet(false, true)
        ) {
            logInfo(TAG, "Firing onAdLoadFailed callback: cache was empty and both branches failed")
            onAdLoadFailed(auctionInfo, error)
        }
    }
}

private const val TAG = "[DenisCache] Callbacks"
