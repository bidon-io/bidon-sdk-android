package org.bidon.sdk.ads.cache.denis.lifecycle

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo

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
    private const val TAG = "CleanupCoordinator"

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
                logInfo(TAG, "AdSource destroyed: demandId=$demandId")
            } catch (e: Exception) {
                logError(TAG, "AdSource.destroy() failed: demandId=$demandId", e)
                // Log but continue - don't propagate cleanup failures
            }
        }
    }

    /**
     * Destroy multiple AdSources in parallel with guaranteed execution.
     *
     * Uses coroutineScope + parallel launch for speed.
     * Each destroy is wrapped individually - one failure doesn't affect others.
     *
     * @param adSources List of (AdSource, demandId) pairs to destroy
     */
    suspend fun destroyAdSourcesParallel(adSources: List<Pair<AdSource<*>, String>>) {
        if (adSources.isEmpty()) return

        withContext(NonCancellable) {
            logInfo(TAG, "Destroying ${adSources.size} AdSources in parallel")

            coroutineScope {
                adSources.forEach { (adSource, demandId) ->
                    launch {
                        try {
                            adSource.destroy()
                            logInfo(TAG, "AdSource destroyed: demandId=$demandId")
                        } catch (e: Exception) {
                            logError(TAG, "AdSource.destroy() failed: demandId=$demandId", e)
                            // Log but continue - don't propagate cleanup failures
                        }
                    }
                }
            }

            logInfo(TAG, "Parallel cleanup completed")
        }
    }

    /**
     * Run arbitrary cleanup block with guaranteed execution.
     *
     * Generic wrapper for any cleanup operation that must complete
     * even during cancellation.
     *
     * @param tag Logging tag for this cleanup operation
     * @param description Description for logging
     * @param block Cleanup block to execute
     */
    suspend fun runGuaranteed(
        tag: String,
        description: String,
        block: suspend () -> Unit
    ) {
        withContext(NonCancellable) {
            try {
                block()
                logInfo(tag, "Cleanup completed: $description")
            } catch (e: Exception) {
                logError(tag, "Cleanup failed: $description", e)
                // Log but continue - don't propagate cleanup failures
            }
        }
    }

    /**
     * Run multiple cleanup blocks in sequence with guaranteed execution.
     *
     * Each block runs independently - failure in one doesn't stop others.
     *
     * @param cleanupBlocks List of (description, block) pairs
     */
    suspend fun runGuaranteedSequence(
        tag: String,
        cleanupBlocks: List<Pair<String, suspend () -> Unit>>
    ) {
        withContext(NonCancellable) {
            cleanupBlocks.forEach { (description, block) ->
                try {
                    block()
                    logInfo(tag, "Cleanup completed: $description")
                } catch (e: Exception) {
                    logError(tag, "Cleanup failed: $description", e)
                    // Continue to next cleanup
                }
            }
        }
    }
}
