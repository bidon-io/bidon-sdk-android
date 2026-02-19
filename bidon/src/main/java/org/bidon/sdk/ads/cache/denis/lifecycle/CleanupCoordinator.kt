package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.logs.logging.impl.logError

/**
 * Coordinates guaranteed cleanup operations during coroutine cancellation.
 *
 * Uses withContext(NonCancellable) to ensure critical cleanup completes
 * even when the parent coroutine is cancelled (e.g., showAd() cancellation).
 *
 * CRITICAL: Cleanup operations must complete to prevent:
 * - Memory leaks from undestroyed AdSource instances
 * - Inconsistent cache state
 * - Missing statistics for cancelled auctions
 *
 * Cleanup failures are logged but don't propagate - ensures all cleanup
 * operations are attempted even if some fail.
 */
internal object CleanupCoordinator {
    private const val TAG = "[DenisCache] CleanupCoordinator"

    /**
     * Destroy single AdSource with guaranteed execution.
     *
     * Wraps AdSource.destroy() in NonCancellable context to ensure
     * cleanup completes even during cancellation.
     *
     * @param adSource AdSource to destroy (nullable for convenience)
     * @param demandId DemandId for logging
     */
    suspend fun destroyAdSource(adSource: AdSource<*>?, demandId: String) {
        if (adSource == null) return

        withContext(NonCancellable) {
            try {
                adSource.destroy()
            } catch (e: Exception) {
                logError(TAG, "AdSource.destroy() failed: demandId=$demandId", e)
                // Log but continue - don't propagate cleanup failures
            }
        }
    }
}
