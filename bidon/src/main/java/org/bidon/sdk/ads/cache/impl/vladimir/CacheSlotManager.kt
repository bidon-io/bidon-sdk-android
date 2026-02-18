package org.bidon.sdk.ads.cache.impl.vladimir

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.ext.ad
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Two-slot priority cache with hot-swap, expiration, and promotion.
 *
 * Slot1 (primary) always holds the highest-priced ad.
 * Slot2 (backup) holds the second-best.
 * On expiration, slot2 promotes to slot1.
 */
internal class CacheSlotManager(private val scope: CoroutineScope) {

    private data class CacheSlot(
        val auctionResult: AuctionResult,
        val observeJob: Job?,
        val price: Double,
        val demandId: String,
    )

    private val slot1 = MutableStateFlow<CacheSlot?>(null)
    private val slot2 = MutableStateFlow<CacheSlot?>(null)

    // === Queries ===

    fun peek(): AuctionResult? = slot1.value?.auctionResult

    fun pop(): AuctionResult? {
        val old = slot1.getAndUpdate { slot2.value }
        slot2.update { null }
        logInfo(TAG, "pop(): removed=${old?.demandId ?: "null"}, promoted slot2→slot1, state=${description()}")
        return old?.auctionResult
    }

    suspend fun poll(): AuctionResult {
        logInfo(TAG, "poll(): waiting for slot1 to be non-null...")
        slot1.first { it != null }
        logInfo(TAG, "poll(): slot1 available, popping")
        return pop()!!
    }

    fun isFull(): Boolean = slot1.value != null && slot2.value != null

    val primaryPrice: Double? get() = slot1.value?.price

    val bestPrice: Double
        get() = maxOf(
            slot1.value?.price ?: 0.0,
            slot2.value?.price ?: 0.0,
        )

    val cachedDemandIds: Set<String>
        get() = setOfNotNull(
            slot1.value?.demandId,
            slot2.value?.demandId,
        )

    val slotCount: Int get() = listOfNotNull(slot1.value, slot2.value).size

    fun description(): String {
        val s1 = slot1.value?.let { "${it.demandId}:${it.price}" } ?: "empty"
        val s2 = slot2.value?.let { "${it.demandId}:${it.price}" } ?: "empty"
        return "[$s1 | $s2]"
    }

    fun logCacheStatus(label: String) {
        val s1 = slot1.value
        val s2 = slot2.value
        val count = listOfNotNull(s1, s2).size
        logInfo(TAG, "── $label | Cache: $count ads loaded ──")
        if (s1 != null) {
            val mode = if (s1.auctionResult is AuctionResult.Bidding) "RTB" else "CPM"
            logInfo(TAG, "  [slot1] ${s1.demandId} / $mode / ${s1.price}")
        }
        if (s2 != null) {
            val mode = if (s2.auctionResult is AuctionResult.Bidding) "RTB" else "CPM"
            logInfo(TAG, "  [slot2] ${s2.demandId} / $mode / ${s2.price}")
        }
        if (count == 0) {
            logInfo(TAG, "  (empty)")
        }
    }

    // === Mutations ===

    /**
     * Insert a successful auction result into the cache.
     *
     * @return `true` if the primary slot (slot1) was updated — either filled or hot-swapped.
     *         The caller can use this to decide whether to fire a callback.
     */
    fun insert(result: AuctionResult): Boolean {
        val price = result.adSource.getStats().price
        val demandId = result.adSource.getStats().demandId.demandId
        logInfo(TAG, "insert(): incoming $demandId @ $price, current state=${description()}")

        val observeJob = observeSlotEvents(result)
        val newSlot = CacheSlot(
            auctionResult = result,
            observeJob = observeJob,
            price = price,
            demandId = demandId,
        )

        val currentSlot1 = slot1.value
        val currentSlot2 = slot2.value

        return when {
            // Slot1 is empty -> fill it
            currentSlot1 == null -> {
                slot1.value = newSlot
                logInfo(TAG, "insert(): slot1 FILLED with $demandId @ $price → state=${description()}")
                true
            }
            // New ad is more expensive -> hot-swap
            price > currentSlot1.price -> {
                logInfo(TAG, "insert(): HOT-SWAP $demandId @ $price replaces ${currentSlot1.demandId} @ ${currentSlot1.price}")
                slot1.value = newSlot

                // Stop observing the demoted ad before emitting notification
                // (prevents removeExpiredSlot from removing it)
                currentSlot1.observeJob?.cancel()
                logInfo(TAG, "insert(): cancelled observe job for demoted ${currentSlot1.demandId}")

                // Notify user that a different creative will be shown
                logInfo(TAG, "insert(): emitting Expired notification for demoted ${currentSlot1.demandId}")
                currentSlot1.auctionResult.adSource.emitEvent(
                    AdEvent.Expired(
                        requireNotNull(currentSlot1.auctionResult.adSource.ad) {
                            "Ad should exist in cache slot"
                        }
                    )
                )

                // Re-subscribe for real expirations and demote to slot2
                val newObserveJob = observeSlotEvents(currentSlot1.auctionResult)
                slot2.value = currentSlot1.copy(observeJob = newObserveJob)
                logInfo(TAG, "insert(): demoted ${currentSlot1.demandId} to slot2 with fresh observe job")
                logInfo(TAG, "insert(): after hot-swap → state=${description()}")
                true
            }
            // Slot2 is empty -> fill backup
            currentSlot2 == null -> {
                slot2.value = newSlot
                logInfo(TAG, "insert(): slot2 FILLED with $demandId @ $price → state=${description()}")
                false
            }
            // New ad beats slot2 -> replace backup
            price > currentSlot2.price -> {
                logInfo(TAG, "insert(): slot2 REPLACED $demandId @ $price over ${currentSlot2.demandId} @ ${currentSlot2.price}")
                slot2.value = newSlot
                destroySlot(currentSlot2)
                logInfo(TAG, "insert(): after replace → state=${description()}")
                false
            }
            // Worse than both slots -> discard
            else -> {
                logInfo(TAG, "insert(): DISCARDED $demandId @ $price (slot1=${currentSlot1.price}, slot2=${currentSlot2.price})")
                observeJob.cancel()
                result.adSource.destroy()
                false
            }
        }
    }

    /**
     * Returns all slot contents WITHOUT removing them from slots.
     * Used to eagerly snapshot ads for persistence (e.g., on pop()).
     */
    fun snapshotAll(): List<AuctionResult> {
        return listOfNotNull(
            slot1.value?.auctionResult,
            slot2.value?.auctionResult,
        )
    }

    /**
     * Removes all ads from slots WITHOUT destroying them.
     * Cancels observe jobs but keeps ad sources alive for reuse.
     */
    fun extractAll(): List<AuctionResult> {
        logInfo(TAG, "extractAll(): current state=${description()}")
        val results = mutableListOf<AuctionResult>()
        val old1 = slot1.getAndUpdate { null }
        val old2 = slot2.getAndUpdate { null }
        old1?.let {
            it.observeJob?.cancel()
            results.add(it.auctionResult)
        }
        old2?.let {
            it.observeJob?.cancel()
            results.add(it.auctionResult)
        }
        logInfo(TAG, "extractAll(): extracted ${results.size} ads → state=${description()}")
        return results
    }

    fun clear() {
        logInfo(TAG, "clear(): destroying all slots, current state=${description()}")
        val old1 = slot1.getAndUpdate { null }
        val old2 = slot2.getAndUpdate { null }
        old1?.let { destroySlot(it) }
        old2?.let { destroySlot(it) }
        logInfo(TAG, "clear(): done → state=${description()}")
    }

    // === Internal ===

    private fun observeSlotEvents(result: AuctionResult): Job {
        val demandId = result.adSource.getStats().demandId.demandId
        logInfo(TAG, "observeSlotEvents(): started observing $demandId")
        return result.adSource.adEvent.onEach { event ->
            logInfo(TAG, "observeSlotEvents(): event=$event for $demandId")
            if (event is AdEvent.Expired) {
                logInfo(TAG, "observeSlotEvents(): Expired event received for $demandId")
                removeExpiredSlot(result)
            }
        }.launchIn(scope)
    }

    private fun removeExpiredSlot(result: AuctionResult) {
        val s1 = slot1.value
        val s2 = slot2.value
        logInfo(TAG, "removeExpiredSlot(): checking, current state=${description()}")
        when {
            s1?.auctionResult === result -> {
                logInfo(TAG, "removeExpiredSlot(): slot1 expired (${s1.demandId}), promoting slot2")
                s1.observeJob?.cancel()
                slot1.value = s2
                slot2.value = null
                logInfo(TAG, "removeExpiredSlot(): after promotion → state=${description()}")
            }
            s2?.auctionResult === result -> {
                logInfo(TAG, "removeExpiredSlot(): slot2 expired (${s2.demandId})")
                s2.observeJob?.cancel()
                slot2.value = null
                logInfo(TAG, "removeExpiredSlot(): after removal → state=${description()}")
            }
            else -> {
                logInfo(TAG, "removeExpiredSlot(): expired result not found in any slot")
            }
        }
    }

    private fun destroySlot(slot: CacheSlot) {
        logInfo(TAG, "destroySlot(): destroying ${slot.demandId} @ ${slot.price}")
        slot.observeJob?.cancel()
        slot.auctionResult.adSource.destroy()
    }
}

private const val TAG = "CacheSlotManager"
