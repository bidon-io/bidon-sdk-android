package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.Job
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Manages auction job lifecycle and cancellation coordination.
 *
 * Handles showAd()-triggered cancellation:
 * - Tracks current auction job by auctionId
 * - Cancels ongoing RTB + CPM processing when showAd() is called
 * - Ensures one cancel per auction (idempotent cancellation)
 *
 * CRITICAL: Successfully loaded ads remain in cache after cancellation.
 * Cancellation only stops ONGOING processing - completed operations are preserved.
 *
 * Thread-safety: Uses synchronized blocks for atomic state updates.
 */
internal class CancellationManager {
    /**
     * Current auction job (nullable when no auction running).
     */
    private var currentAuctionJob: Job? = null

    /**
     * Current auction ID for tracking.
     */
    private var currentAuctionId: String? = null

    /**
     * Lock for thread-safe state updates.
     */
    private val lock = Any()

    /**
     * Register a new auction job.
     *
     * Called when starting a new auction in coordinateAuction().
     * Any previous auction job is NOT cancelled - it may have loaded ads into cache.
     *
     * @param auctionId Unique auction identifier
     * @param job Coroutine job for the auction
     */
    fun registerAuction(auctionId: String, job: Job) {
        synchronized(lock) {
            // Log if replacing existing auction (shouldn't happen normally)
            if (currentAuctionJob?.isActive == true && currentAuctionId != auctionId) {
                logInfo(TAG, "Warning: registering new auction while previous still active " +
                    "(prev=$currentAuctionId, new=$auctionId)")
            }

            currentAuctionId = auctionId
            currentAuctionJob = job
            logInfo(TAG, "Auction registered: auctionId=$auctionId")
        }
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
    fun cancelIfMatching(auctionId: String): Boolean {
        synchronized(lock) {
            // Only cancel if auctionId matches current auction
            if (currentAuctionId != auctionId) {
                logInfo(TAG, "No cancellation: auctionId mismatch (showing=$auctionId, current=$currentAuctionId)")
                return false
            }

            val job = currentAuctionJob
            if (job == null || !job.isActive) {
                logInfo(TAG, "No cancellation: auction already completed (auctionId=$auctionId)")
                return false
            }

            // Cancel the auction job
            logInfo(TAG, "Cancelling auction: auctionId=$auctionId")
            job.cancel()

            // Clear state (prevent double cancellation)
            currentAuctionJob = null
            currentAuctionId = null

            return true
        }
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
        synchronized(lock) {
            val job = currentAuctionJob
            if (job == null || !job.isActive) {
                logInfo(TAG, "No active auction to cancel")
                return false
            }

            logInfo(TAG, "Cancelling current auction: auctionId=$currentAuctionId")
            job.cancel()

            // Clear state
            currentAuctionJob = null
            currentAuctionId = null

            return true
        }
    }

    /**
     * Check if auction is currently running.
     *
     * @return true if an active auction job exists
     */
    fun isAuctionRunning(): Boolean {
        synchronized(lock) {
            return currentAuctionJob?.isActive == true
        }
    }

    /**
     * Get current auction ID (for logging/debugging).
     *
     * @return Current auction ID or null if no auction running
     */
    fun getCurrentAuctionId(): String? {
        synchronized(lock) {
            return if (currentAuctionJob?.isActive == true) currentAuctionId else null
        }
    }

    /**
     * Clear state when auction completes normally.
     *
     * Called when auction finishes successfully (not cancelled).
     * Prevents stale state from affecting future operations.
     *
     * @param auctionId Auction ID that completed
     */
    fun onAuctionCompleted(auctionId: String) {
        synchronized(lock) {
            if (currentAuctionId == auctionId) {
                logInfo(TAG, "Auction completed normally: auctionId=$auctionId")
                currentAuctionJob = null
                currentAuctionId = null
            }
        }
    }

    companion object {
        private const val TAG = "[DenisCache] CancellationManager"
    }
}
