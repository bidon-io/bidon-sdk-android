package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Lifecycle management facade that coordinates lifecycle components.
 *
 * Responsibilities:
 * - Manages AdInstanceScope (instance-scoped CoroutineScope)
 * - Controls PeriodicSweepJob lifecycle (start/stop)
 * - Coordinates auction cancellation via CancellationManager
 *
 * Design pattern: Facade - single entry point for lifecycle management.
 * Encapsulates wiring between components (AdInstanceScope -> PeriodicSweepJob dependency).
 *
 * CRITICAL: This is NOT a singleton. Each ad instance (Interstitial/Rewarded/Banner)
 * gets its own LifecycleManager. This aligns with Phase 4 decision:
 * "Instance-scoped sweep jobs (each ad instance manages its own)".
 *
 * Lifecycle flow:
 * 1. Create LifecycleManager when ad instance is created
 * 2. Call start() on first cache() call (idempotent)
 * 3. Register auction jobs during cold start
 * 4. Cancel auctions on showAd() or destroyAd()
 * 5. Call stop() on destroyAd() to clean up background tasks
 *
 * Thread-safety: Delegates to thread-safe components.
 */
internal class LifecycleManager {
    /**
     * Instance-scoped coroutine scope for this ad instance.
     * Provides scope for launching auctions and periodic jobs.
     */
    private val adInstanceScope = AdInstanceScope()

    /**
     * Periodic sweep job that removes expired cache entries every 5 minutes.
     * Automatically stops when adInstanceScope is cancelled.
     */
    private val periodicSweepJob = PeriodicSweepJob(adInstanceScope)

    /**
     * Manages auction job lifecycle and cancellation coordination.
     * Tracks current auction and handles showAd()-triggered cancellation.
     */
    private val cancellationManager = CancellationManager()

    /**
     * Tracks whether lifecycle has been started.
     * Ensures start() is idempotent (safe to call multiple times).
     */
    @Volatile
    private var isStarted = false

    /**
     * Start lifecycle management.
     *
     * Starts PeriodicSweepJob for cache maintenance.
     * Should be called on first cache() call.
     *
     * Idempotent: safe to call multiple times.
     * Subsequent calls are no-ops if already started.
     */
    fun start() {
        if (isStarted) {
            logInfo(TAG, "Lifecycle already started, skipping")
            return
        }

        logInfo(TAG, "Starting lifecycle management")
        periodicSweepJob.start()
        isStarted = true
    }

    /**
     * Stop lifecycle management.
     *
     * Stops PeriodicSweepJob and cancels AdInstanceScope.
     * Should be called on destroyAd() to prevent zombie background tasks.
     *
     * After stop(), no further auction operations should be performed.
     */
    fun stop() {
        logInfo(TAG, "Stopping lifecycle management")

        // Stop periodic sweep job
        periodicSweepJob.stop()

        // Cancel any running auctions
        cancellationManager.cancelCurrent()

        // Cancel instance scope (stops all coroutines)
        adInstanceScope.cancel()

        isStarted = false
    }

    /**
     * Get coroutine scope for launching auction operations.
     *
     * @return CoroutineScope for auction coroutines
     */
    fun getScope(): CoroutineScope = adInstanceScope.scope

    /**
     * Register a new auction job.
     *
     * Called when starting a new auction in coordinateAuction().
     * Enables cancellation via cancelAuction() or cancelCurrent().
     *
     * @param auctionId Unique auction identifier
     * @param job Coroutine job for the auction
     */
    fun registerAuction(auctionId: String, job: Job) {
        cancellationManager.registerAuction(auctionId, job)
    }

    /**
     * Cancel auction if matching auctionId.
     *
     * Called from showAd() to stop ongoing processing for the shown ad's auction.
     * Uses auctionId matching to avoid cancelling unrelated auctions.
     *
     * Idempotent: calling twice with same auctionId only cancels once.
     *
     * @param auctionId Auction ID of the ad being shown
     * @return true if auction was cancelled, false if no match or already completed
     */
    fun cancelAuction(auctionId: String): Boolean {
        return cancellationManager.cancelIfMatching(auctionId)
    }

    /**
     * Cancel current auction unconditionally.
     *
     * Called when ad instance is destroyed (destroyAd() / clear()).
     * Cancels any running auction regardless of auctionId.
     *
     * @return true if an active auction was cancelled
     */
    fun cancelCurrent(): Boolean {
        return cancellationManager.cancelCurrent()
    }

    /**
     * Clear auction state when completed normally (not cancelled).
     *
     * Called after auction finishes successfully or fails.
     * Prevents stale auctionId from blocking future cancellation checks.
     *
     * @param auctionId Auction ID that completed
     */
    fun onAuctionCompleted(auctionId: String) {
        cancellationManager.onAuctionCompleted(auctionId)
    }

    companion object {
        private const val TAG = "[DenisCache] LifecycleManager"
    }
}
