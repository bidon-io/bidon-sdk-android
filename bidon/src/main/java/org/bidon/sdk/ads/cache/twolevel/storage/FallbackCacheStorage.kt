package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Fallback cache storage for the Two-Level Cache strategy.
 *
 * Port of iOS FallbackCacheStorage.swift (Zhenya strategy). Simpler than [CacheStorage]:
 * no sticky mode, no iteration threshold. Sorted array with capacity-based eviction.
 *
 * Eviction rule: strict `price > cheapest.price` required (iOS code uses `>`, not `>=`).
 * Equal-price items do NOT displace the current cheapest when the cache is full.
 *
 * Thread safety: all mutating operations use [Mutex].
 * A [headSnapshot] volatile field provides a lock-free isReady check.
 */
internal class FallbackCacheStorage(
    private val capacity: Int,
) {
    private val mutex = Mutex()

    // Items sorted descending by price
    private val items = mutableListOf<AuctionResult>()

    // Non-suspend snapshot for synchronous isReady() checks.
    @Volatile
    private var headSnapshot: AuctionResult? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Insert [element] into the fallback cache.
     *
     * @return [InsertResult.Success] or [InsertResult.Rejected] with reason [InsertResult.Reason.CacheFull].
     */
    @Suppress("ReturnCount")
    suspend fun insert(element: AuctionResult): InsertResult = mutex.withLock {
        val price = element.price()
        val key = element.demandKey()

        // Duplicate check by demandId
        val existingIndex = items.indexOfFirst { it.demandKey() == key }
        if (existingIndex >= 0) {
            val existingPrice = items[existingIndex].price()
            if (existingPrice == price) {
                // Same price: update in-place
                items[existingIndex] = element
                items.sortByDescending { it.price() }
                headSnapshot = items.firstOrNull()
                logInfo(TAG, "[Fallback] insert UPDATED in-place: key=$key price=$price")
                logCacheState()
                return@withLock InsertResult.Success
            } else {
                // Different price: remove old, fall through to re-insert
                items.removeAt(existingIndex)
            }
        }

        // Capacity eviction — strict > required (iOS: `guard element.price > cheapest.price`)
        if (items.size >= capacity) {
            val cheapest = items.minByOrNull { it.price() }
            if (cheapest == null || price <= cheapest.price()) {
                // price == cheapest.price is also rejected (strict >)
                logInfo(TAG, "[Fallback] insert REJECTED CacheFull: price=$price cheapest=${cheapest?.price()}")
                logCacheState()
                return@withLock InsertResult.Rejected(InsertResult.Reason.CacheFull)
            }
            items.remove(cheapest)
            logInfo(TAG, "[Fallback] evicted ${cheapest.demandKey()} price=${cheapest.price()}")
            cheapest.adSource.destroy()
        }

        items.add(element)
        items.sortByDescending { it.price() }
        headSnapshot = items.firstOrNull()
        logInfo(TAG, "[Fallback] insert SUCCESS: key=$key price=$price size=${items.size}")
        logCacheState()
        InsertResult.Success
    }

    /**
     * Remove and return the highest-priced item (head).
     */
    suspend fun popFirst(): AuctionResult? = mutex.withLock {
        if (items.isEmpty()) return@withLock null
        val head = items.removeAt(0)
        headSnapshot = items.firstOrNull()
        logInfo(TAG, "[Fallback] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    /** Returns the head item without removing it. Suspends for Mutex. */
    suspend fun peek(): AuctionResult? = mutex.withLock { items.firstOrNull() }

    /** Non-suspend snapshot for synchronous isReady checks. May be stale by one operation. */
    fun peekSnapshot(): AuctionResult? = headSnapshot

    // -----------------------------------------------------------------------
    // Logging helpers
    // -----------------------------------------------------------------------

    private fun logCacheState() {
        if (items.isEmpty()) {
            logInfo(TAG, "[Fallback] Cache: empty")
            return
        }
        val entries = items.map { "${it.price()}" }
        logInfo(TAG, "[Fallback] Cache (${items.size}/$capacity): [${entries.joinToString(", ")}]")
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}

private fun AuctionResult.price(): Double = adSource.getStats().price
private fun AuctionResult.demandKey(): String = adSource.getStats().demandId.demandId
