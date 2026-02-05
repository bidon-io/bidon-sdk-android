package org.bidon.sdk.ads.cache.denis.lifecycle

import android.app.Activity
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo
import java.lang.ref.WeakReference

/**
 * Validates Activity context references during periodic cache sweeps.
 *
 * PROBLEM: Singleton caches can retain AdSource instances with Activity references
 * for up to 30 minutes (TTL). If Activity is destroyed but AdSource retained,
 * the entire Activity view hierarchy is leaked.
 *
 * SOLUTION: Use WeakReference pattern + periodic validation.
 * - AdSource implementations SHOULD use WeakReference<Activity> internally
 * - During sweep, check if Activity references are still valid
 * - Remove entries with invalid references to prevent memory leaks
 *
 * NOTE: This validator checks the cache-level context, not AdSource internals.
 * AdSource implementations are responsible for their own WeakReference management.
 * This validator provides a safety net for entries that should be cleaned up.
 *
 * Thread-safety: Called from PeriodicSweepJob on Dispatchers.Default.
 */
internal object WeakContextValidator {
    private const val TAG = "WeakContextValidator"

    /**
     * Interface for cache entries that track Activity context.
     *
     * AdSource implementations can implement this to support validation.
     * If AdSource doesn't implement this, it's assumed to be valid
     * (trust adapter implementation).
     */
    interface ContextAware {
        /**
         * Check if the Activity context is still valid.
         *
         * @return true if context is valid (Activity not destroyed)
         */
        fun isContextValid(): Boolean
    }

    /**
     * Validate all cache entries and remove invalid ones.
     *
     * Called during periodic sweep (every 5 minutes).
     * Checks ReadyToShowCache entries for invalid Activity references.
     *
     * @return Number of entries removed due to invalid context
     */
    suspend fun validateAndCleanup(): Int {
        val invalidEntries = mutableListOf<String>()

        // Get all entries from ReadyToShowCache
        val entries = ReadyToShowCache.getAll()

        for (entry in entries) {
            val adSource = extractAdSource(entry)
            if (adSource == null) {
                // Can't extract AdSource - skip validation
                continue
            }

            // Check if AdSource implements ContextAware
            if (adSource is ContextAware) {
                if (!adSource.isContextValid()) {
                    logInfo(TAG, "Invalid Activity context detected: demandId=${entry.demandId}")
                    invalidEntries.add(entry.demandId)

                    // Destroy AdSource before removing from cache
                    CleanupCoordinator.destroyAdSource(adSource, entry.demandId)
                }
            }
            // If AdSource doesn't implement ContextAware, trust adapter implementation
        }

        // Remove invalid entries from cache
        invalidEntries.forEach { demandId ->
            ReadyToShowCache.remove(demandId)
            logInfo(TAG, "Removed entry with invalid Activity context: demandId=$demandId")
        }

        if (invalidEntries.isNotEmpty()) {
            logInfo(TAG, "WeakReference validation: removed ${invalidEntries.size} invalid entries")
        }

        return invalidEntries.size
    }

    /**
     * Extract AdSource from cache entry.
     *
     * AuctionResult is sealed - extract AdSource based on subtype.
     *
     * @param entry Cache entry containing AuctionResult
     * @return AdSource or null if cannot extract
     */
    private fun extractAdSource(entry: CacheEntry<AuctionResult>): AdSource<*>? {
        return when (val result = entry.value) {
            is AuctionResult.Network -> result.adSource
            is AuctionResult.Bidding -> result.adSource
            is AuctionResult.AuctionFailed -> null // Failed auctions have no AdSource
        }
    }

    /**
     * Create WeakReference wrapper for Activity.
     *
     * Utility function for AdSource implementations to use WeakReference pattern.
     *
     * @param activity Activity to wrap
     * @return WeakReference<Activity>
     */
    fun createWeakRef(activity: Activity): WeakReference<Activity> {
        return WeakReference(activity)
    }

    /**
     * Check if Activity WeakReference is still valid.
     *
     * Checks both reference null and Activity lifecycle state.
     *
     * @param activityRef WeakReference to Activity
     * @return true if Activity is still valid (not null, not finishing, not destroyed)
     */
    fun isActivityValid(activityRef: WeakReference<Activity>?): Boolean {
        val activity = activityRef?.get() ?: return false
        return !activity.isFinishing && !activity.isDestroyed
    }
}
