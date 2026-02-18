package org.bidon.sdk.ads.cache.denis.stores

import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Thread-safe singleton cache for storing loaded ads ready to show.
 *
 * Stores AuctionResult entries that have been successfully loaded and are ready for display.
 * Enables warm start optimization - when cache is not empty, onAdLoaded fires immediately.
 *
 * Ordering: FIFO with price-sorted insertion. Ads are inserted in eCPM descending order,
 * so the first element is always the most expensive. popFirst() returns the most expensive ad.
 *
 * Thread-safety: Uses synchronized blocks for consistent read/write access.
 * Expiration: Lazy eviction on access + periodic sweep via external job.
 * Capacity: No limit.
 *
 * Application-wide scope: Singleton object persists between ad instances.
 */
internal object ReadyToShowCache {
    private const val TAG = "[DenisCache] ReadyToShowCache"

    /**
     * Thread-safe sorted list: ordered by eCPM descending (most expensive first).
     * All access must be synchronized on [lock].
     */
    private val entries = mutableListOf<CacheEntry<AuctionResult>>()
    private val lock = Any()

    /**
     * Store an ad in cache with price-sorted insertion (eCPM descending).
     *
     * - Evicts expired entries first (lazy cleanup)
     * - Inserts entry at correct position to maintain eCPM descending order
     *
     * @param entry Cache entry to store
     */
    fun put(entry: CacheEntry<AuctionResult>) {
        synchronized(lock) {
            evictExpiredLocked()

            // Find insertion position to maintain eCPM descending order
            val insertIndex = entries.indexOfFirst { it.ecpm < entry.ecpm }
            if (insertIndex == -1) {
                entries.add(entry) // Lowest ecpm or empty list, add at end
            } else {
                entries.add(insertIndex, entry)
            }

            logInfo(TAG, "ReadyToShowCache.put: demandId=${entry.demandId}, uid=${entry.uid}, ecpm=${entry.ecpm}, size=${entries.size}")
        }
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
        synchronized(lock) {
            val entry = entries.find { it.uid == uid } ?: return null
            return if (entry.isExpired()) {
                entries.remove(entry)
                null
            } else {
                entry.value
            }
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
        synchronized(lock) {
            val entry = entries.find { it.uid == uid } ?: return null
            entries.remove(entry)
            return if (entry.isExpired()) null else entry.value
        }
    }

    /**
     * Get entry with highest eCPM (first in sorted list).
     *
     * @return Entry with highest eCPM or null if cache empty/all expired
     */
    fun getBest(): CacheEntry<AuctionResult>? {
        synchronized(lock) {
            evictExpiredLocked()
            return entries.firstOrNull()
        }
    }

    /**
     * Get all non-expired cache entries.
     *
     * @return List of all valid entries
     */
    fun getAll(): List<CacheEntry<AuctionResult>> {
        synchronized(lock) {
            evictExpiredLocked()
            return entries.toList()
        }
    }

    /**
     * Check if cache is empty after evicting expired entries.
     *
     * @return true if no valid entries remain
     */
    fun isEmpty(): Boolean {
        synchronized(lock) {
            evictExpiredLocked()
            return entries.isEmpty()
        }
    }

    /**
     * Get count of valid (non-expired) entries.
     *
     * @return Number of entries in cache
     */
    fun size(): Int {
        synchronized(lock) {
            evictExpiredLocked()
            return entries.size
        }
    }

    /**
     * Get maximum eCPM across all entries (first entry since sorted descending).
     *
     * @return Highest eCPM in cache or 0.0 if empty
     */
    fun getMaxEcpm(): Double {
        synchronized(lock) {
            evictExpiredLocked()
            return entries.firstOrNull()?.ecpm ?: 0.0
        }
    }

    /**
     * Peek at ad by uid without removing it.
     *
     * @param uid Unique ad unit identifier
     * @return Cached AuctionResult or null if not found/expired
     */
    fun peek(uid: String): AuctionResult? {
        synchronized(lock) {
            val entry = entries.find { it.uid == uid } ?: return null
            return if (entry.isExpired()) {
                entries.remove(entry)
                null
            } else {
                entry.value
            }
        }
    }

    /**
     * Peek at best ad without removing it.
     *
     * @return AuctionResult with highest eCPM or null if empty
     */
    fun peekBest(): AuctionResult? = getBest()?.value

    /**
     * Remove and return first ad (highest eCPM due to sorted insertion).
     *
     * FIFO pop: returns and removes the most expensive ad.
     *
     * @return Entry with highest eCPM or null if empty
     */
    fun popFirst(): CacheEntry<AuctionResult>? {
        synchronized(lock) {
            evictExpiredLocked()
            if (entries.isEmpty()) return null
            return entries.removeAt(0)
        }
    }

    /**
     * Remove and return best ad (highest eCPM).
     *
     * Delegates to popFirst() since list is sorted by eCPM descending.
     *
     * @return Entry with highest eCPM or null if empty
     */
    fun popBest(): CacheEntry<AuctionResult>? = popFirst()

    /**
     * Check if ad exists in cache without retrieving it.
     *
     * @param uid Unique ad unit identifier
     * @return true if valid (non-expired) entry exists
     */
    fun contains(uid: String): Boolean {
        synchronized(lock) {
            val entry = entries.find { it.uid == uid } ?: return false
            return if (entry.isExpired()) {
                entries.remove(entry)
                false
            } else {
                true
            }
        }
    }

    /**
     * Clear all entries from cache.
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    /**
     * Remove all expired entries from cache (periodic sweep).
     * Called by PeriodicSweepJob every 5 minutes.
     *
     * @return Number of entries removed
     */
    fun sweep(): Int {
        synchronized(lock) {
            val sizeBefore = entries.size
            evictExpiredLocked()
            val removed = sizeBefore - entries.size
            if (removed > 0) {
                logInfo(TAG, "ReadyToShowCache sweep: removed $removed expired entries")
            }
            return removed
        }
    }

    /**
     * Remove all expired entries from cache (lazy eviction).
     *
     * Must be called while holding [lock].
     */
    private fun evictExpiredLocked() {
        val now = TtlConfig.now()
        val iterator = entries.iterator()
        var removed = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now > entry.expiresAt) {
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
     */
    fun logDetailedState() {
        synchronized(lock) {
            evictExpiredLocked()
            val allEntries = entries.toList()
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
}
