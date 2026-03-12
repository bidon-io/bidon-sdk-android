package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Main cache storage for the Two-Level Cache (V6) strategy.
 *
 * Port of iOS CacheStorage.swift (Zhenya strategy). Maintains a sorted array of
 * AuctionResult items with sticky-head mode and per-iteration threshold filtering.
 *
 * Thread safety: all mutating operations use [Mutex].
 * A [headSnapshot] volatile field provides a lock-free isReady check.
 */
internal class CacheStorage(
    private val capacity: Int,
    private val iterationThreshold: Int, // percentage, e.g. 80
) {
    private val mutex = Mutex()

    // Items sorted descending by price
    private val items = mutableListOf<AuctionResult>()

    // demandId -> index in items; rebuilt after every structural change
    private val indexByKey = mutableMapOf<String, Int>()

    private var stickyHeadActive = false
    private var iterationMaxPrice: Double? = null

    // Non-suspend snapshot for synchronous isReady() checks.
    // Updated inside the Mutex at the end of every mutating operation.
    // Worst-case: returns a stale value — acceptable for isReady semantics.
    @Volatile
    private var headSnapshot: AuctionResult? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Resets the iteration-threshold state. Call before each auction round. */
    suspend fun beginIteration() = mutex.withLock {
        iterationMaxPrice = null
        logInfo(TAG, "[Main] beginIteration | cache: ${items.size}/$capacity")
    }

    /**
     * Insert [element] into the cache.
     *
     * @param sticky if true, the element is promoted to head and protected from eviction.
     * @return [InsertResult.Success] or [InsertResult.Rejected] with a reason.
     */
    @Suppress("ReturnCount")
    suspend fun insert(
        element: AuctionResult,
        sticky: Boolean,
    ): InsertResult = mutex.withLock {
        val price = element.price()
        val key = element.demandKey()

        // 1. Iteration threshold (capacity > 1 only — matches iOS guard)
        if (shouldRejectByIterationThreshold(price)) {
            logInfo(TAG, "[Main] insert REJECTED IterationThreshold: price=$price minAllowed=${formatMinAllowed()}")
            logCacheState()
            return@withLock InsertResult.Rejected(InsertResult.Reason.IterationThreshold)
        }

        // 2. Duplicate check by demandId
        val existingIndex = indexByKey[key]
        if (existingIndex != null) {
            val existingPrice = items[existingIndex].price()
            if (existingPrice == price) {
                // Same price: protect sticky head from non-sticky update
                if (stickyHeadActive && existingIndex == 0 && !sticky) {
                    logInfo(TAG, "[Main] insert REJECTED StickyHeadProtected (update): key=$key price=$price")
                    logCacheState()
                    return@withLock InsertResult.Rejected(InsertResult.Reason.StickyHeadProtected)
                }
                // Update in-place
                items[existingIndex] = element
                if (sticky && existingIndex != 0) {
                    promoteToStickyHead(existingIndex)
                }
                sortAccordingToMode()
                rebuildIndex()
                trimIfNeeded()
                headSnapshot = items.firstOrNull()
                logInfo(TAG, "[Main] insert UPDATED in-place: key=$key price=$price")
                logCacheState()
                return@withLock InsertResult.Success
            } else {
                // Different price: remove old entry, fall through to re-insert
                if (stickyHeadActive && existingIndex == 0) {
                    stickyHeadActive = false
                }
                items.removeAt(existingIndex)
                rebuildIndex()
            }
        }

        // 3. Sticky head protection for capacity == 1
        if (capacity == 1 && stickyHeadActive && items.isNotEmpty()) {
            logInfo(TAG, "[Main] insert REJECTED StickyHeadProtected: key=$key price=$price")
            logCacheState()
            return@withLock InsertResult.Rejected(InsertResult.Reason.StickyHeadProtected)
        }

        // 4. Capacity check: only insert if new item beats the cheapest evictable
        if (items.size >= capacity) {
            val cheapest = cheapestAllowedToEvict()
            if (cheapest == null || price <= cheapest.price()) {
                logInfo(TAG, "[Main] insert REJECTED CacheFull: price=$price cheapest=${cheapest?.price()}")
                logCacheState()
                return@withLock InsertResult.Rejected(InsertResult.Reason.CacheFull)
            }
            // Evict cheapest to make room
            items.remove(cheapest)
            rebuildIndex()
            logInfo(TAG, "[Main] evicted ${cheapest.demandKey()} price=${cheapest.price()}")
            cheapest.adSource.destroy()
        }

        // 5. Insert, sort according to mode, trim overflow
        items.add(element)
        if (sticky) stickyHeadActive = true
        sortAccordingToMode()
        val evicted = trimIfNeeded()
        evicted.forEach { it.adSource.destroy() }
        headSnapshot = items.firstOrNull()

        logInfo(TAG, "[Main] insert SUCCESS: key=$key price=$price sticky=$sticky size=${items.size}")
        logCacheState()
        InsertResult.Success
    }

    /**
     * Remove and return the highest-priced item (head).
     * Disables sticky mode and re-sorts the remaining items.
     */
    suspend fun popFirst(): AuctionResult? = mutex.withLock {
        if (items.isEmpty()) return@withLock null
        val head = items.removeAt(0)
        indexByKey.remove(head.demandKey())
        stickyHeadActive = false
        sortAccordingToMode()
        rebuildIndex()
        headSnapshot = items.firstOrNull()
        logInfo(TAG, "[Main] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    /** Returns the head item without removing it. Suspends for Mutex. */
    suspend fun peek(): AuctionResult? = mutex.withLock { items.firstOrNull() }

    /** Non-suspend snapshot for synchronous isReady checks. May be stale by one operation. */
    fun peekSnapshot(): AuctionResult? = headSnapshot

    // -----------------------------------------------------------------------
    // Private helpers — all called with Mutex already held
    // -----------------------------------------------------------------------

    /**
     * iOS shouldRejectByIterationThreshold logic:
     *  - First item (iterationMaxPrice == null): set max, pass.
     *  - price > currentMax: update max, pass.
     *  - price >= currentMax * (threshold/100): pass.
     *  - otherwise: reject.
     *
     * Only active when capacity > 1.
     */
    private fun shouldRejectByIterationThreshold(price: Double): Boolean {
        if (capacity <= 1) return false
        val currentMax = iterationMaxPrice
        return if (currentMax == null || price > currentMax) {
            iterationMaxPrice = price
            false // new maximum — always pass
        } else if (price >= currentMax * (iterationThreshold / 100.0)) {
            false // within threshold — pass
        } else {
            true // below threshold — reject
        }
    }

    /**
     * Returns the cheapest item eligible for eviction.
     *
     * In sticky mode the head (items[0]) is protected, so the cheapest
     * candidate comes from the tail (items[1..]).
     * In normal mode cheapest is items.last() (array is sorted descending).
     */
    private fun cheapestAllowedToEvict(): AuctionResult? {
        return if (stickyHeadActive && items.size > 1) {
            items.subList(1, items.size).minByOrNull { it.price() }
        } else if (!stickyHeadActive && items.isNotEmpty()) {
            items.last()
        } else {
            null
        }
    }

    /**
     * iOS sortAccordingToMode:
     *  - Sticky mode: sort only tail (items[1..]), keep head in place.
     *  - Normal mode: sort all items.
     */
    private fun sortAccordingToMode() {
        if (stickyHeadActive && items.size > 1) {
            val head = items[0]
            val sortedTail = items.subList(1, items.size).sortedByDescending { it.price() }
            items.clear()
            items.add(head)
            items.addAll(sortedTail)
        } else {
            items.sortByDescending { it.price() }
        }
        rebuildIndex()
    }

    /**
     * iOS trimIfNeeded: remove from tail while over capacity.
     * The sticky head (items[0]) is never removed because trim always
     * removes items.last() and the sticky head is at index 0.
     *
     * Returns evicted items so callers can destroy their ad sources.
     */
    private fun trimIfNeeded(): List<AuctionResult> {
        val evicted = mutableListOf<AuctionResult>()
        while (items.size > capacity) {
            val removed = items.removeLast()
            evicted.add(removed)
        }
        rebuildIndex()
        return evicted
    }

    private fun promoteToStickyHead(idx: Int) {
        val element = items.removeAt(idx)
        items.add(0, element)
        stickyHeadActive = true
    }

    private fun rebuildIndex() {
        indexByKey.clear()
        items.forEachIndexed { i, item -> indexByKey[item.demandKey()] = i }
    }

    // -----------------------------------------------------------------------
    // Logging helpers
    // -----------------------------------------------------------------------

    private fun logCacheState() {
        if (items.isEmpty()) {
            logInfo(TAG, "[Main] Cache: empty")
            return
        }
        val entries = items.mapIndexed { i, item ->
            val sticky = if (stickyHeadActive && i == 0) "*" else ""
            "${item.price()}$sticky"
        }
        logInfo(TAG, "[Main] Cache (${items.size}/$capacity): [${entries.joinToString(", ")}]")
    }

    private fun formatMinAllowed(): String {
        val max = iterationMaxPrice ?: return "n/a"
        return "${max * iterationThreshold / 100.0}"
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}

private fun AuctionResult.price(): Double = adSource.getStats().price
private fun AuctionResult.demandKey(): String = adSource.getStats().demandId.demandId
