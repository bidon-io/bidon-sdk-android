package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Immutable snapshot of main cache state.
 * Exposed via [CacheStorage.state] for lock-free, consistent reads.
 */
internal data class CacheSnapshot(
    val head: AuctionResult? = null,
    val isFull: Boolean = false,
    val thresholdBar: Double? = null,
    val size: Int = 0,
) {
    val hasContent: Boolean get() = head != null

    companion object {
        val Empty = CacheSnapshot()
    }
}

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
 * Thread safety: mutations use [Mutex]. State is exposed via [StateFlow] for lock-free reads.
 */
internal class CacheStorage(
    private val capacity: Int,
    private val threshold: Int,
) {
    private val mutex = Mutex()
    private val items = mutableListOf<AuctionResult>()
    private var stickyHeadActive = false

    private val _state = MutableStateFlow(CacheSnapshot.Empty)
    val state: StateFlow<CacheSnapshot> = _state.asStateFlow()

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
            emitState()
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
        val bar = maxPrice * (threshold / 100.0)
        if (price < bar) {
            logInfo(TAG, "[Main] insert REJECTED Threshold: price=$price bar=$bar max=$maxPrice")
            logCacheState()
            return@withLock InsertResult.Rejected(InsertResult.Reason.Threshold)
        }

        // 4. Duplicate (same demandId + same price) -> remove old, fall through to insert.
        val existingIndex = items.indexOfFirst { it.demandKey() == key && it.price() == price }
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
        emitState()

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
        emitState()
        logInfo(TAG, "[Main] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    // -----------------------------------------------------------------------
    // Private helpers — all called with Mutex already held
    // -----------------------------------------------------------------------

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

    private fun emitState() {
        _state.value = CacheSnapshot(
            head = items.firstOrNull(),
            isFull = items.size >= capacity,
            thresholdBar = items.firstOrNull()?.let { it.price() * (threshold / 100.0) },
            size = items.size,
        )
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
