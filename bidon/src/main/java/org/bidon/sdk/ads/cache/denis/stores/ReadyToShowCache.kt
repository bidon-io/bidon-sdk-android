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
 * Capacity: Configurable limit (default 3) to prevent memory exhaustion (CACHE-09).
 * Duplicate policy: Replaces only if new eCPM is higher (CACHE-07).
 *
 * Application-wide scope: Singleton object persists between ad instances (CACHE-03).
 */
internal object ReadyToShowCache {
    private const val TAG = "ReadyToShowCache"
    private const val DEFAULT_CAPACITY = 3

    /**
     * Thread-safe storage: demandId -> CacheEntry<AuctionResult>
     */
    private val cache = ConcurrentHashMap<String, CacheEntry<AuctionResult>>()

    /**
     * Configurable capacity limit (range 1-10, default 3).
     */
    private var capacity = DEFAULT_CAPACITY

    /**
     * Configure cache capacity.
     *
     * @param newCapacity Capacity limit (1-10), clamped to valid range
     */
    fun setCapacity(newCapacity: Int) {
        capacity = newCapacity.coerceIn(1, 10)
        logInfo(TAG, "ReadyToShowCache capacity set to $capacity")
    }

    /**
     * Store an ad in cache with capacity and duplicate checks.
     *
     * - Evicts expired entries first (lazy cleanup)
     * - Checks capacity limit, evicts lowest eCPM if at limit
     * - Stores entry keyed by demandId
     *
     * Thread-safe: ConcurrentHashMap operations are atomic.
     *
     * @param entry Cache entry to store
     */
    fun put(entry: CacheEntry<AuctionResult>) {
        evictExpired()

        // Check capacity, evict lowest eCPM if at limit
        if (cache.size >= capacity) {
            evictLowestEcpm()
        }

        cache[entry.demandId] = entry
        logInfo(TAG, "ReadyToShowCache.put: demandId=${entry.demandId}, ecpm=${entry.ecpm}, size=${cache.size}")
    }

    /**
     * Retrieve ad by demandId with lazy expiration check.
     *
     * Returns null if not found or expired. Expired entries are removed.
     *
     * @param demandId Demand network identifier
     * @return Cached AuctionResult or null
     */
    fun get(demandId: String): AuctionResult? {
        val entry = cache[demandId] ?: return null
        return if (entry.isExpired()) {
            cache.remove(demandId)
            null
        } else {
            entry.value
        }
    }

    /**
     * Remove and return ad by demandId.
     *
     * Returns null if not found or expired.
     *
     * @param demandId Demand network identifier
     * @return Cached AuctionResult or null
     */
    fun remove(demandId: String): AuctionResult? {
        val entry = cache.remove(demandId) ?: return null
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
     * Peek at ad by demandId without removing it.
     *
     * Non-destructive read for checking cache state.
     *
     * @param demandId Demand network identifier
     * @return Cached AuctionResult or null if not found/expired
     */
    fun peek(demandId: String): AuctionResult? {
        val entry = cache[demandId] ?: return null
        return if (entry.isExpired()) {
            cache.remove(demandId)
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
     * @param demandId Demand network identifier
     * @return true if valid (non-expired) entry exists
     */
    fun contains(demandId: String): Boolean {
        val entry = cache[demandId] ?: return false
        return if (entry.isExpired()) {
            cache.remove(demandId)
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
     * Remove all expired entries from cache (lazy eviction).
     *
     * Called internally before query operations to ensure clean state.
     */
    private fun evictExpired() {
        val now = TtlConfig.now()
        val sizeBefore = cache.size
        cache.entries.removeIf { (_, entry) -> now > entry.expiresAt }
        val removed = sizeBefore - cache.size
        if (removed > 0) {
            logInfo(TAG, "ReadyToShowCache evicted $removed expired entries")
        }
    }

    /**
     * Evict entry with lowest eCPM (LRU policy when at capacity).
     */
    private fun evictLowestEcpm() {
        val lowest = cache.entries.minByOrNull { it.value.ecpm }
        lowest?.let {
            cache.remove(it.key)
            logInfo(TAG, "ReadyToShowCache evicted lowest eCPM entry: demandId=${it.key}, ecpm=${it.value.ecpm}")
        }
    }
}
