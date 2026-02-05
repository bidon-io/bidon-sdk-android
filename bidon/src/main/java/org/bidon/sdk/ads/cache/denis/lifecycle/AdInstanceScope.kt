package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Instance-scoped CoroutineScope for ad lifecycle management.
 *
 * Features:
 * - SupervisorJob: child failures don't cancel siblings (sweep failure won't crash auction)
 * - Dispatchers.Default: background thread pool for non-blocking operations
 * - cancel(): stops all coroutines when ad instance is destroyed
 *
 * Usage:
 * - Create one per ad instance (Interstitial/Rewarded/Banner instance)
 * - Launch periodic jobs via scope
 * - Call cancel() in destroyAd() / clear() to stop all background work
 *
 * CRITICAL: This scope is NOT application-wide. Each ad instance gets its own scope.
 * When the ad instance is destroyed, the scope is cancelled, stopping all background tasks.
 */
internal class AdInstanceScope {
    /**
     * Coroutine scope with SupervisorJob for failure isolation.
     * SupervisorJob ensures sweep failures don't crash auction processing.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Check if scope is still active.
     * @return true if scope has not been cancelled
     */
    val isActive: Boolean
        get() = scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /**
     * Cancel all coroutines in this scope.
     * Called when ad instance is destroyed (LIFE-04: sweep job stops with ad instance).
     */
    fun cancel() {
        scope.cancel()
    }
}
