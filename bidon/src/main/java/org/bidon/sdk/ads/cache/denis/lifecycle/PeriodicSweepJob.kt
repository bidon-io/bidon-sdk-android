package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.ads.cache.denis.stores.TtlConfig
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Periodic cache sweep job that removes expired entries every 5 minutes.
 *
 * Features:
 * - while(isActive) + delay() pattern: cooperative cancellation
 * - SupervisorJob isolation: sweep failures don't crash ad instance
 * - Logs sweep results for monitoring
 *
 * CRITICAL: Job automatically stops when AdInstanceScope is cancelled (LIFE-04).
 * No zombie background tasks after ad instance destroyed.
 *
 * Thread-safety: Coroutine-based, runs on Dispatchers.Default.
 */
internal class PeriodicSweepJob(
    private val adInstanceScope: AdInstanceScope,
) {
    private var sweepJob: Job? = null

    /**
     * Start periodic sweep job.
     *
     * First sweep runs after 5 minutes (not immediately on start).
     * Continues every 5 minutes until cancelled.
     *
     * Safe to call multiple times - subsequent calls are no-ops if job is running.
     */
    fun start() {
        if (sweepJob?.isActive == true) {
            logInfo(TAG, "Sweep job already running, skipping start")
            return
        }

        sweepJob = adInstanceScope.scope.launch {
            logInfo(TAG, "Periodic sweep job started (interval=${TtlConfig.SWEEP_INTERVAL_MILLIS}ms)")

            while (isActive) {
                // Wait first, then sweep (first sweep after 5 minutes)
                delay(TtlConfig.SWEEP_INTERVAL_MILLIS)
                performSweep()
            }
        }
    }

    /**
     * Stop periodic sweep job.
     * Called when ad instance is destroyed.
     */
    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
        logInfo(TAG, "Periodic sweep job stopped")
    }

    /**
     * Perform cache sweep operation.
     *
     * Sweeps both ReadyToShowCache and RtbPayloadCache.
     * Failures are logged but don't propagate (SupervisorJob isolation).
     */
    private suspend fun performSweep() {
        logInfo(TAG, "Starting periodic sweep")

        try {
            // Step 1: Sweep expired entries from caches
            val readyRemoved = ReadyToShowCache.sweep()
            val rtbRemoved = RtbPayloadCache.sweep()

            // Step 2: Validate WeakReferences and remove invalid entries (LIFE-07)
            val contextInvalid = WeakContextValidator.validateAndCleanup()

            logInfo(
                TAG,
                "Sweep completed: expired=$readyRemoved+$rtbRemoved, contextInvalid=$contextInvalid, " +
                    "ReadyToShow size=${ReadyToShowCache.size()}, RtbPayload size=${RtbPayloadCache.size()}"
            )
        } catch (e: Exception) {
            // Log but don't propagate - SupervisorJob prevents crash
            logError(TAG, "Sweep failed, will retry next interval", e)
        }
    }

    companion object {
        private const val TAG = "[DenisCache] PeriodicSweepJob"
    }
}
