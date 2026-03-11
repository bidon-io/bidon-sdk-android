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
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Two-slot cache with expiration and promotion.
 *
 * Slot1 (primary) holds whichever ad filled it first.
 * Slot2 (backup) holds the second ad.
 * Slot1 is never replaced — new ads go to slot2 or are discarded.
 * On expiration, slot2 promotes to slot1.
 */
internal class CacheSlotManager(private val scope: CoroutineScope) {

    private data class CacheSlot(
        val auctionResult: AuctionResult,
        val auctionInfo: AuctionInfo,
        val observeJob: Job?,
        val price: Double,
        val demandId: String,
    )

    /**
     * Called when a slot becomes empty due to expiration.
     * The orchestrator uses this to trigger cache replenishment.
     */
    var onSlotVacancy: (() -> Unit)? = null

    private val slot1 = MutableStateFlow<CacheSlot?>(null)
    private val slot2 = MutableStateFlow<CacheSlot?>(null)

    // === Queries ===

    fun peek(): AuctionResult? = slot1.value?.auctionResult

    fun peekAuctionInfo(): AuctionInfo? = slot1.value?.auctionInfo

    fun pop(): AuctionResult? {
        val old = slot1.getAndUpdate { slot2.value }
        slot2.update { null }
        old?.observeJob?.cancel()
        logInfo(TAG, "pop(): removed=${old?.demandId ?: "null"}, promoted slot2→slot1, state=${description()}")
        return old?.auctionResult
    }

    /**
     * Suspends until slot1 becomes non-null.
     * Does NOT pop — the caller decides how to consume.
     */
    suspend fun awaitAvailable() {
        logInfo(TAG, "awaitAvailable(): waiting for slot1 to be non-null...")
        slot1.first { it != null }
        logInfo(TAG, "awaitAvailable(): slot1 available")
    }

    fun isFull(): Boolean = slot1.value != null && slot2.value != null

    val primaryPrice: Double? get() = slot1.value?.price

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
        val slot1Desc = s1?.let {
            val mode = if (it.auctionResult is AuctionResult.Bidding) "RTB" else "CPM"
            "${it.demandId}/$mode/${it.price}"
        } ?: "empty"
        val slot2Desc = s2?.let {
            val mode = if (it.auctionResult is AuctionResult.Bidding) "RTB" else "CPM"
            "${it.demandId}/$mode/${it.price}"
        } ?: "empty"
        logInfo(TAG, "$label: slot1=$slot1Desc, slot2=$slot2Desc")
    }

    // === Mutations ===

    /**
     * Insert a successful auction result into the cache.
     *
     * Fills empty slots in order: slot1 first, then slot2.
     * If both slots are occupied, replaces slot2 only if the new ad has a higher price.
     * Slot1 is never replaced — it holds whichever ad filled it first.
     *
     * @return `true` if slot1 was filled (was empty before).
     *         The caller uses this to decide whether to fire onSuccess.
     *         Returns `false` for all slot2 operations and discards.
     */
    fun insert(result: AuctionResult, auctionInfo: AuctionInfo): Boolean {
        val price = result.price
        val demandId = result.demandId
        logInfo(TAG, "insert(): incoming $demandId @ $price, current state=${description()}")

        val observeJob = observeSlotEvents(result)
        val newSlot = CacheSlot(
            auctionResult = result,
            auctionInfo = auctionInfo,
            observeJob = observeJob,
            price = price,
            demandId = demandId,
        )

        val currentSlot1 = slot1.value
        val currentSlot2 = slot2.value

        return when {
            // Slot1 is empty -> fill primary
            currentSlot1 == null -> {
                slot1.value = newSlot
                logInfo(TAG, "insert(): slot1 FILLED with $demandId @ $price")
                true
            }
            // Slot1 occupied, slot2 is empty -> fill backup
            currentSlot2 == null -> {
                slot2.value = newSlot
                logInfo(TAG, "insert(): slot2 FILLED with $demandId @ $price")
                false
            }
            // Both occupied, new ad beats slot2 -> replace backup
            price > currentSlot2.price -> {
                slot2.value = newSlot
                destroySlot(currentSlot2)
                logInfo(TAG, "insert(): slot2 REPLACED $demandId @ $price over ${currentSlot2.demandId} @ ${currentSlot2.price}")
                false
            }
            // Both occupied, worse than or equal to slot2 -> discard
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
    fun snapshotAll(): List<CachedAd> {
        return listOfNotNull(
            slot1.value?.let { CachedAd(it.auctionResult, it.auctionInfo) },
            slot2.value?.let { CachedAd(it.auctionResult, it.auctionInfo) },
        )
    }

    /**
     * Removes all ads from slots WITHOUT destroying them.
     * Cancels observe jobs but keeps ad sources alive for reuse.
     */
    fun extractAll(): List<CachedAd> {
        logInfo(TAG, "extractAll(): current state=${description()}")
        val results = mutableListOf<CachedAd>()
        val old1 = slot1.getAndUpdate { null }
        val old2 = slot2.getAndUpdate { null }
        old1?.let {
            it.observeJob?.cancel()
            results.add(CachedAd(it.auctionResult, it.auctionInfo))
        }
        old2?.let {
            it.observeJob?.cancel()
            results.add(CachedAd(it.auctionResult, it.auctionInfo))
        }
        logInfo(TAG, "extractAll(): extracted ${results.size} ads → state=${description()}")
        return results
    }

    /**
     * Updates the [AuctionInfo] stored in all occupied slots.
     *
     * During the waterfall loop, [insert] is called with a preliminary [AuctionInfo]
     * that has no round statistics (adUnits / noBids are null). Once the round
     * finishes and [RoundStat] is available, the orchestrator calls this method
     * to replace the preliminary info with the complete one.
     */
    fun updateAuctionInfo(auctionInfo: AuctionInfo) {
        slot1.value?.let { slot1.value = it.copy(auctionInfo = auctionInfo) }
        slot2.value?.let { slot2.value = it.copy(auctionInfo = auctionInfo) }
        logInfo(TAG, "updateAuctionInfo(): updated auctionInfo in ${slotCount} slot(s)")
    }

    fun evictBackup() {
        val old = slot2.getAndUpdate { null }
        if (old != null) {
            logInfo(TAG, "evictBackup(): destroying ${old.demandId} @ ${old.price}")
            old.observeJob?.cancel()
            old.auctionResult.adSource.destroy()
        }
    }

    // === Internal ===

    private fun observeSlotEvents(result: AuctionResult): Job {
        val demandId = result.demandId
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
        val slotRemoved = when {
            s1?.auctionResult === result -> {
                logInfo(TAG, "removeExpiredSlot(): slot1 expired (${s1.demandId}), promoting slot2")
                s1.observeJob?.cancel()
                s1.auctionResult.adSource.destroy()
                slot1.value = s2
                slot2.value = null
                logInfo(TAG, "removeExpiredSlot(): after promotion → state=${description()}")
                true
            }
            s2?.auctionResult === result -> {
                logInfo(TAG, "removeExpiredSlot(): slot2 expired (${s2.demandId})")
                s2.observeJob?.cancel()
                s2.auctionResult.adSource.destroy()
                slot2.value = null
                logInfo(TAG, "removeExpiredSlot(): after removal → state=${description()}")
                true
            }
            else -> {
                logInfo(TAG, "removeExpiredSlot(): expired result not found in any slot")
                false
            }
        }
        if (slotRemoved) {
            onSlotVacancy?.invoke()
        }
    }

    private fun destroySlot(slot: CacheSlot) {
        logInfo(TAG, "destroySlot(): destroying ${slot.demandId} @ ${slot.price}")
        slot.observeJob?.cancel()
        slot.auctionResult.adSource.destroy()
    }
}

private const val TAG = "AdCacheVladimir.CacheSlotManager"
