package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Fallback cache storage for the Two-Level Cache strategy. Simpler than [CacheStorage]:
 * no sticky mode, no iteration threshold. Sorted array with capacity-based eviction.
 *
 * When [capacity] is 0, fallback is disabled — all inserts are rejected.
 *
 * Eviction rule: strict `price > cheapest.price` required.
 * Equal-price items do NOT displace the current cheapest when the cache is full.
 *
 * Thread safety: all mutating operations use [Mutex].
 * Volatile snapshot fields provide lock-free checks from any thread.
 */
internal class FallbackCacheStorage(
    private val capacity: Int,
) {
    private val mutex = Mutex()

    // Items sorted descending by price
    private val items = mutableListOf<AuctionResult>()

    @Volatile
    private var headSnapshot: AuctionResult? = null

    /** Volatile snapshot: true when capacity is 0 (disabled) or items.size >= capacity. */
    @Volatile
    var isFull: Boolean = capacity <= 0
        private set

    /** Volatile snapshot of cheapest item price, or null when empty/disabled. */
    @Volatile
    var cheapestPrice: Double? = null
        private set

    /** True when fallback is disabled (capacity <= 0). */
    val isDisabled: Boolean get() = capacity <= 0

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
        // Disabled fallback — reject everything
        if (capacity <= 0) {
            return@withLock InsertResult.Rejected(InsertResult.Reason.CacheFull)
        }

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
                updateSnapshots()
                logInfo(TAG, "[Fallback] insert UPDATED in-place: key=$key price=$price")
                logCacheState()
                return@withLock InsertResult.Success
            } else {
                // Different price: remove old, fall through to re-insert
                items.removeAt(existingIndex)
            }
        }

        // Capacity eviction — strict > required
        if (items.size >= capacity) {
            val cheapest = items.minByOrNull { it.price() }
            if (cheapest == null || price <= cheapest.price()) {
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
        updateSnapshots()
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
        updateSnapshots()
        logInfo(TAG, "[Fallback] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    /** Returns the head item without removing it. Suspends for Mutex. */
    suspend fun peek(): AuctionResult? = mutex.withLock { items.firstOrNull() }

    /** Non-suspend snapshot for synchronous isReady checks. May be stale by one operation. */
    fun peekSnapshot(): AuctionResult? = headSnapshot

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun updateSnapshots() {
        headSnapshot = items.firstOrNull()
        isFull = capacity <= 0 || items.size >= capacity
        cheapestPrice = items.lastOrNull()?.price()
    }

    private fun logCacheState() {
        if (capacity <= 0) {
            logInfo(TAG, "[Fallback] disabled (capacity=0)")
            return
        }
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
