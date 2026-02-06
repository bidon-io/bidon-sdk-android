package org.bidon.sdk.ads.cache.denis.stores

import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton cache for storing loaded ads ready to show.
 *
 * Stores AuctionResult entries that have been successfully loaded and are ready for display.
 * Enables warm start optimization - when cache is not empty, onAdLoaded fires immediately.
 *
 * Thread-safety: Uses ConcurrentHashMap for lock-free concurrent access.
 * Expiration: Lazy eviction on access (CACHE-05) + periodic sweep via external job.
 * Capacity: Unlimited storage with TTL-based eviction only (no size limit).
 * Duplicate policy: Replaces only if new eCPM is higher (CACHE-07).
 *
 * Application-wide scope: Singleton object persists between ad instances (CACHE-03).
 */
internal object ReadyToShowCache {
    private const val TAG = "[DenisCache] ReadyToShowCache"

    /**
     * Thread-safe storage: uid -> CacheEntry<AuctionResult>
     * Key is AdUnit.uid for true uniqueness (allows multiple ads from same demandId)
     */
    private val cache = ConcurrentHashMap<String, CacheEntry<AuctionResult>>()

    /**
     * Store an ad in cache.
     *
     * - Evicts expired entries first (lazy cleanup)
     * - Stores entry keyed by uid (unique per ad unit)
     * - No capacity limit (unlimited storage, TTL-based eviction only)
     *
     * Thread-safe: ConcurrentHashMap operations are atomic.
     *
     * @param entry Cache entry to store
     */
    fun put(entry: CacheEntry<AuctionResult>) {
        evictExpired()

        cache[entry.uid] = entry
        logInfo(TAG, "ReadyToShowCache.put: demandId=${entry.demandId}, uid=${entry.uid}, ecpm=${entry.ecpm}, size=${cache.size}")
    }

    /**
     * Retrieve ad by uid with lazy expiration check.
     *
     * Returns null if not found or expired. Expired entries are removed.
     *
     * @param uid Unique ad unit identifier
     * @return Cached AuctionResult or null
     */
    fun get(uid: String): AuctionResult? {
        val entry = cache[uid] ?: return null
        return if (entry.isExpired()) {
            cache.remove(uid)
            null
        } else {
            entry.value
        }
    }

    /**
     * Remove and return ad by uid.
     *
     * Returns null if not found or expired.
     *
     * @param uid Unique ad unit identifier
     * @return Cached AuctionResult or null
     */
    fun removeByUid(uid: String): AuctionResult? {
        val entry = cache.remove(uid) ?: return null
        return if (entry.isExpired()) null else entry.value
    }

    /**
     * Get entry with highest eCPM.
     *
     * Used for LIFE-01 showAd() selection - choose best ad to display.
     *
     * @return Entry with highest eCPM or null if cache empty/all expired
     */
    fun getBest(): CacheEntry<AuctionResult>? {
        evictExpired()
        return cache.values.maxByOrNull { it.ecpm }
    }

    /**
     * Get all non-expired cache entries.
     *
     * @return List of all valid entries
     */
    fun getAll(): List<CacheEntry<AuctionResult>> {
        evictExpired()
        return cache.values.toList()
    }

    /**
     * Check if cache is empty after evicting expired entries.
     *
     * @return true if no valid entries remain
     */
    fun isEmpty(): Boolean {
        evictExpired()
        return cache.isEmpty()
    }

    /**
     * Get count of valid (non-expired) entries.
     *
     * @return Number of entries in cache
     */
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    /**
     * Get maximum eCPM across all entries.
     *
     * Used for dynamic pricefloor calculation (AUCTION-05).
     *
     * @return Highest eCPM in cache or 0.0 if empty
     */
    fun getMaxEcpm(): Double {
        evictExpired()
        return cache.values.maxOfOrNull { it.ecpm } ?: 0.0
    }

    /**
     * Peek at ad by uid without removing it.
     *
     * Non-destructive read for checking cache state.
     *
     * @param uid Unique ad unit identifier
     * @return Cached AuctionResult or null if not found/expired
     */
    fun peek(uid: String): AuctionResult? {
        val entry = cache[uid] ?: return null
        return if (entry.isExpired()) {
            cache.remove(uid)
            null
        } else {
            entry.value
        }
    }

    /**
     * Peek at best ad without removing it.
     *
     * Non-destructive read for checking best available ad.
     *
     * @return AuctionResult with highest eCPM or null if empty
     */
    fun peekBest(): AuctionResult? = getBest()?.value

    /**
     * Remove and return best ad (highest eCPM).
     *
     * Used in showAd() flow to atomically remove winner ad from cache.
     *
     * @return Entry with highest eCPM or null if empty
     */
    fun popBest(): CacheEntry<AuctionResult>? {
        evictExpired()
        val best = cache.entries.maxByOrNull { it.value.ecpm }
        return best?.let {
            cache.remove(it.key)
            it.value
        }
    }

    /**
     * Check if ad exists in cache without retrieving it.
     *
     * Quick existence check with expiration validation.
     *
     * @param uid Unique ad unit identifier
     * @return true if valid (non-expired) entry exists
     */
    fun contains(uid: String): Boolean {
        val entry = cache[uid] ?: return false
        return if (entry.isExpired()) {
            cache.remove(uid)
            false
        } else {
            true
        }
    }

    /**
     * Clear all entries from cache.
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Remove all expired entries from cache (periodic sweep).
     * Called by PeriodicSweepJob every 5 minutes.
     *
     * @return Number of entries removed
     */
    fun sweep(): Int {
        val sizeBefore = cache.size
        evictExpired()
        val removed = sizeBefore - cache.size
        if (removed > 0) {
            logInfo(TAG, "ReadyToShowCache sweep: removed $removed expired entries")
        }
        return removed
    }

    /**
     * Remove all expired entries from cache (lazy eviction).
     *
     * Called internally before query operations to ensure clean state.
     */
    private fun evictExpired() {
        val now = TtlConfig.now()
        var removed = 0
        // Using iterator instead of removeIf() for API 23 compatibility
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now > entry.value.expiresAt) {
                iterator.remove()
                removed++
            }
        }
        if (removed > 0) {
            logInfo(TAG, "ReadyToShowCache evicted $removed expired entries")
        }
    }

    /**
     * Log detailed cache state for debugging.
     *
     * Shows full cache contents with all ads sorted by eCPM descending.
     * Useful for understanding cache composition and troubleshooting.
     *
     * Usage: ReadyToShowCache.logDetailedState()
     */
    fun logDetailedState() {
        evictExpired()
        val allEntries = cache.values.toList().sortedByDescending { it.ecpm }
        val maxEcpm = allEntries.firstOrNull()?.ecpm ?: 0.0

        logInfo(TAG, "=== CACHE STATE: size=${allEntries.size}, maxEcpm=${"$%.2f".format(maxEcpm)} ===")

        allEntries.forEachIndexed { index, entry ->
            val timeLeft = (entry.expiresAt - TtlConfig.now()) / 1000 // seconds
            logInfo(
                TAG,
                "  [${index + 1}] ${entry.demandId}: " +
                    "ecpm=${"$%.2f".format(entry.ecpm)}, " +
                    "uid=${entry.uid.take(8)}..., " +
                    "auctionId=${entry.auctionId.take(8)}..., " +
                    "ttl=${timeLeft}s"
            )
        }

        if (allEntries.isEmpty()) {
            logInfo(TAG, "  (cache is empty)")
        }

        logInfo(TAG, "=== END CACHE STATE ===")
    }
}
