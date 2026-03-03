package org.bidon.sdk.ads.cache.denis.stores

import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton cache for RTB bid response payloads.
 *
 * Stores RTB payloads from completed auctions that weren't loaded, enabling reuse
 * in subsequent loadAd() calls without token collection (skip-token optimization).
 *
 * Features:
 * - Atomic duplicate detection with eCPM comparison (higher eCPM always wins)
 * - Lazy eviction on access (expired entries removed when queried)
 * - Limited to MAX_RTB_PAYLOADS=10 with lowest-eCPM eviction policy
 * - Thread-safe using ConcurrentHashMap with atomic compute()
 */
internal class RtbPayloadCache {
    private val cache = ConcurrentHashMap<String, CacheEntry<RtbPayload>>()

    private val TAG = "[DenisCache] RtbPayloadCache"
    private val MAX_RTB_PAYLOADS = 10

    /**
     * Inserts payload only if new eCPM is higher than existing (atomic operation).
     *
     * Uses atomic compute() to prevent race conditions in duplicate detection (SAFETY-02).
     * If demandId already exists with higher/equal eCPM, keeps existing entry.
     * Evicts lowest eCPM entry if at capacity (MAX_RTB_PAYLOADS=10).
     *
     * @param payload RTB payload to cache
     * @return true if inserted, false if existing had higher eCPM
     */
    fun putIfHigherEcpm(payload: RtbPayload): Boolean {
        evictExpired()

        // Check capacity, evict lowest eCPM if at limit
        if (cache.size >= MAX_RTB_PAYLOADS) {
            evictLowestEcpm()
        }

        val demandId = payload.adUnit.demandId
        val newEcpm = payload.adUnit.pricefloor
        var wasInserted = false

        // ATOMIC operation - prevents race condition (SAFETY-02)
        // Using synchronized instead of compute() for API 23 compatibility
        synchronized(cache) {
            val existing = cache[demandId]
            if (existing == null || existing.isExpired() || newEcpm > existing.ecpm) {
                wasInserted = true
                val newEntry = CacheEntry.create(
                    value = payload,
                    ecpm = newEcpm,
                    demandId = demandId,
                    auctionId = payload.auctionId
                )
                cache[demandId] = newEntry
            }
            // else: Keep existing if higher eCPM
        }

        return wasInserted
    }

    /**
     * Retrieves payload by demandId (lazy expiration check).
     *
     * @param demandId Demand network identifier
     * @return Payload if exists and not expired, null otherwise
     */
    fun get(demandId: String): RtbPayload? {
        val entry = cache[demandId] ?: return null
        return if (entry.isExpired()) {
            cache.remove(demandId)
            null
        } else {
            entry.value
        }
    }

    /**
     * Removes and returns payload by demandId.
     *
     * @param demandId Demand network identifier
     * @return Payload if existed and not expired, null otherwise
     */
    fun remove(demandId: String): RtbPayload? {
        val entry = cache.remove(demandId) ?: return null
        return if (entry.isExpired()) null else entry.value
    }

    /**
     * Returns all non-expired cache entries.
     *
     * @return List of all valid cache entries
     */
    fun getAll(): List<CacheEntry<RtbPayload>> {
        evictExpired()
        return cache.values.toList()
    }

    /**
     * Returns all non-expired entries sorted by eCPM descending (highest first).
     *
     * Used for RTB processing order optimization.
     *
     * @return List of entries sorted by eCPM (highest to lowest)
     */
    fun getAllSortedByEcpm(): List<CacheEntry<RtbPayload>> {
        evictExpired()
        return cache.values.sortedByDescending { it.ecpm }
    }

    /**
     * Checks if cache is empty (excluding expired entries).
     *
     * @return true if no valid entries
     */
    fun isEmpty(): Boolean {
        evictExpired()
        return cache.isEmpty()
    }

    /**
     * Returns number of valid cache entries (excluding expired).
     *
     * @return Cache size
     */
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    /**
     * Returns maximum eCPM among cached entries.
     *
     * Used for dynamic pricefloor calculation.
     *
     * @return Maximum eCPM or 0.0 if cache empty
     */
    fun getMaxEcpm(): Double {
        evictExpired()
        return cache.values.maxOfOrNull { it.ecpm } ?: 0.0
    }

    /**
     * Checks if demandId is in cache (lazy expiration check).
     *
     * @param demandId Demand network identifier
     * @return true if entry exists and not expired
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
     * Returns set of all cached demandIds (excluding expired).
     *
     * Used for skip-token optimization (skip token collection for cached demands).
     *
     * @return Set of demandIds
     */
    fun getCachedDemandIds(): Set<String> {
        evictExpired()
        return cache.keys.toSet()
    }

    /**
     * Clears all cache entries.
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
            logInfo(TAG, "RtbPayloadCache sweep: removed $removed expired entries")
        }
        return removed
    }

    /**
     * Removes all expired entries from cache.
     */
    private fun evictExpired() {
        val now = TtlConfig.now()
        // Using iterator instead of removeIf() for API 23 compatibility
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now > entry.value.expiresAt) {
                iterator.remove()
            }
        }
    }

    /**
     * Evict RTB payload with lowest eCPM when cache is at capacity.
     *
     * Called when cache.size >= MAX_RTB_PAYLOADS to make room for new entry.
     * Keeps highest eCPM payloads for future cold start optimizations.
     */
    private fun evictLowestEcpm() {
        val lowest = cache.entries.minByOrNull { it.value.ecpm }
        lowest?.let {
            cache.remove(it.key)
            logInfo(TAG, "EVICT: Removed lowest eCPM RTB: demandId=${it.key}, ecpm=${"$%.2f".format(it.value.ecpm)}, size=${cache.size}")
        }
    }
}
