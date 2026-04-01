package org.bidon.sdk.ads.cache.twolevel.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Immutable snapshot of fallback cache state.
 * Exposed via [FallbackCacheStorage.state] for lock-free, consistent reads.
 */
internal data class FallbackSnapshot(
    val head: AuctionResult? = null,
    val isFull: Boolean = false,
    val cheapestPrice: Double? = null,
    val size: Int = 0,
) {
    val hasContent: Boolean get() = head != null
}

/**
 * Fallback cache storage for the Two-Level Cache strategy. Simpler than [CacheStorage]:
 * no sticky mode, no threshold. Sorted array with capacity-based eviction.
 *
 * When [capacity] is 0, fallback is disabled — all inserts are rejected.
 *
 * Eviction rule: strict `price > cheapest.price` required.
 * Equal-price items do NOT displace the current cheapest when the cache is full.
 *
 * Thread safety: mutations use [synchronized]. State is exposed via [StateFlow] for lock-free reads.
 */
internal class FallbackCacheStorage(
    private val capacity: Int,
) {
    private val lock = Any()
    private val items = mutableListOf<AuctionResult>()

    private val _state = MutableStateFlow(FallbackSnapshot(isFull = capacity <= 0))
    val state: StateFlow<FallbackSnapshot> = _state.asStateFlow()

    val isDisabled: Boolean = capacity <= 0

    /**
     * Insert [element] into the fallback cache.
     *
     * @return [InsertResult.Success] or [InsertResult.Rejected] with reason [InsertResult.Reason.CacheFull].
     */
    @Suppress("ReturnCount")
    fun insert(element: AuctionResult): InsertResult = synchronized(lock) {
        if (capacity <= 0) {
            return@synchronized InsertResult.Rejected(InsertResult.Reason.CacheFull)
        }

        val price = element.price()
        val key = element.demandKey()

        // Duplicate check (same demandId + same price) — matches Main cache logic
        val existingIndex = items.indexOfFirst { it.demandKey() == key && it.price() == price }
        if (existingIndex >= 0) {
            items.removeAt(existingIndex)
        }

        // Capacity eviction — strict > required
        var evicted: AuctionResult? = null
        if (items.size >= capacity) {
            val cheapest = items.minByOrNull { it.price() }
            if (cheapest == null || price <= cheapest.price()) {
                logInfo(TAG, "[Fallback] insert REJECTED CacheFull: price=$price cheapest=${cheapest?.price()}")
                logCacheState()
                return@synchronized InsertResult.Rejected(InsertResult.Reason.CacheFull)
            }
            items.remove(cheapest)
            evicted = cheapest
            logInfo(TAG, "[Fallback] evicted ${cheapest.demandKey()} price=${cheapest.price()}")
        }

        items.add(element)
        items.sortByDescending { it.price() }
        emitState()
        logInfo(TAG, "[Fallback] insert SUCCESS: key=$key price=$price size=${items.size}")
        logCacheState()
        InsertResult.Success(evicted = evicted)
    }

    /**
     * Remove and return the highest-priced item (head).
     */
    fun popFirst(): AuctionResult? = synchronized(lock) {
        if (items.isEmpty()) return@synchronized null
        val head = items.removeAt(0)
        emitState()
        logInfo(TAG, "[Fallback] popFirst: ${head.demandKey()} price=${head.price()} remaining=${items.size}")
        logCacheState()
        head
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun emitState() {
        _state.value = FallbackSnapshot(
            head = items.firstOrNull(),
            isFull = capacity <= 0 || items.size >= capacity,
            cheapestPrice = items.lastOrNull()?.price(),
            size = items.size,
        )
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
