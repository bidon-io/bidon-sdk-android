package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Main cache storage for the Two-Level Cache strategy. Maintains a sorted array of
 * [AuctionResult] items ordered by price descending. Capacity = [capacity].
 *
 * ### Sticky head (spec section 3.1)
 * The first inserted bid (sticky=true) is protected at the head. It is never displaced
 * by a later insert. [popFirst] clears sticky mode.
 *
 * ### Threshold (spec section 3.2)
 * When the cache is non-empty, only bids with `price >= thresholdBar` are accepted.
 * `thresholdBar = items[0].price * (threshold / 100)`.
 * The first bid always passes (sets maxPrice).
 *
 * ### No eviction (spec section 3.3)
 * Caller verifies `!isFull` before calling [insert]. The storage never evicts items.
 *
 * Thread safety: all mutating operations use [Mutex].
 * [peekSnapshot] and [isFull] are volatile for lock-free reads.
 */
internal class CacheStorage(
    private val capacity: Int,
    private val threshold: Int, // percentage 0-100
) {
    private val mutex = Mutex()

    // Items sorted descending by price. items[0] = highest price (head).
    private val items = mutableListOf<AuctionResult>()

    private var stickyHeadActive = false

    @Volatile
    private var headSnapshot: AuctionResult? = null

    /** Volatile snapshot: true when items.size >= capacity. Lock-free read for callers. */
    @Volatile
    var isFull: Boolean = false
        private set

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Insert [element] into the cache.
     *
     * Algorithm (spec section 3.3):
     *  1. Empty cache -> accept (sets maxPrice).
     *  2. capacity==1 + sticky active + not sticky -> REJECTED (StickyProtected).
     *  3. price < thresholdBar -> REJECTED (Threshold).
     *  4. Duplicate (same demandId) -> remove old, insert new.
     *  5. INSERT + sort (sticky stays at head).
     *
     * @param sticky if true, marks this element as the sticky head.
     * @return [InsertResult.Success] or [InsertResult.Rejected].
     */
    @Suppress("ReturnCount")
    suspend fun insert(
        element: AuctionResult,
        sticky: Boolean,
    ): InsertResult = mutex.withLock {
        val price = element.price()
        val key = element.demandKey()

        // 1. Empty cache -> always accept.
        if (items.isEmpty()) {
            items.add(element)
            if (sticky) stickyHeadActive = true
            updateSnapshots()
            logInfo(TAG, "[Main] insert SUCCESS (first): key=$key price=$price sticky=$sticky")
            logCacheState()
            return@withLock InsertResult.Success
        }

        // 2. capacity==1 + sticky active + not sticky -> reject.
        if (capacity == 1 && stickyHeadActive && !sticky) {
            logInfo(TAG, "[Main] insert REJECTED StickyProtected: key=$key price=$price")
            logCacheState()
            return@withLock InsertResult.Rejected(InsertResult.Reason.StickyProtected)
        }

        // 3. Threshold check: price < thresholdBar -> reject.
        val maxPrice = items[0].price()
        val thresholdBar = maxPrice * (threshold / 100.0)
        if (price < thresholdBar) {
            logInfo(TAG, "[Main] insert REJECTED Threshold: price=$price bar=$thresholdBar max=$maxPrice")
            logCacheState()
            return@withLock InsertResult.Rejected(InsertResult.Reason.Threshold)
        }

        // 4. Duplicate by demandId -> remove old, fall through to insert.
        val existingIndex = items.indexOfFirst { it.demandKey() == key }
        if (existingIndex >= 0) {
            if (stickyHeadActive && existingIndex == 0) {
                stickyHeadActive = false
            }
            items.removeAt(existingIndex)
        }

        // 5. Insert + sort (sticky stays at head).
        items.add(element)
        if (sticky) stickyHeadActive = true
        sortItems()
        updateSnapshots()

        logInfo(TAG, "[Main] insert SUCCESS: key=$key price=$price sticky=$sticky size=${items.size}")
        logCacheState()
        InsertResult.Success
    }

    /**
     * Remove and return the highest-priced item (head). Clears sticky mode.
     */
    suspend fun popFirst(): AuctionResult? = mutex.withLock {
        if (items.isEmpty()) return@withLock null
        val head = items.removeAt(0)
        stickyHeadActive = false
        updateSnapshots()
        logInfo(TAG, "[Main] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    /** Returns the best item without removing it. Suspends for [Mutex]. */
    suspend fun peek(): AuctionResult? = mutex.withLock { items.firstOrNull() }

    /** Non-suspend volatile snapshot for lock-free isReady checks. */
    fun peekSnapshot(): AuctionResult? = headSnapshot

    // -----------------------------------------------------------------------
    // Private helpers — all called with Mutex already held
    // -----------------------------------------------------------------------

    /**
     * Sort by price descending, keeping sticky head pinned at index 0.
     */
    private fun sortItems() {
        if (stickyHeadActive && items.size > 1) {
            val head = items[0]
            val sortedTail = items.subList(1, items.size).sortedByDescending { it.price() }
            items.clear()
            items.add(head)
            items.addAll(sortedTail)
        } else {
            items.sortByDescending { it.price() }
        }
    }

    private fun updateSnapshots() {
        headSnapshot = items.firstOrNull()
        isFull = items.size >= capacity
    }

    private fun logCacheState() {
        if (items.isEmpty()) {
            logInfo(TAG, "[Main] Cache: empty")
            return
        }
        val entries = items.mapIndexed { i, item ->
            val s = if (stickyHeadActive && i == 0) "*" else ""
            "${item.price()}$s"
        }
        logInfo(TAG, "[Main] Cache (${items.size}/$capacity): [${entries.joinToString(", ")}]")
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
