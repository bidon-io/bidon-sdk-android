package org.bidon.sdk.ads.cache.impl.andr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.utils.SdkDispatchers
import java.util.SortedSet
import kotlin.coroutines.CoroutineContext

internal class AuctionResultBuffer(
    coroutineContext: CoroutineContext = SdkDispatchers.IO,
    capacity: Int = 2,
) : AdBuffer<AuctionResult, AuctionResultBuffer.Entry>(capacity, AdBuffer.Entry.PriceComparator) {
    private val coroutineScope: CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

    private val observers = MutableStateFlow<MutableMap<Entry, Job>>(mutableMapOf())

    init {
        require(capacity >= 1) { "Capacity must be >= 1, got $capacity" }

        coroutineScope.launch {
            entries.collect {
                createObservers(it)
            }
        }
    }

    override fun insert(vararg items: AuctionResult) {
        entries.update { old ->
            val updated =
                entrySet().apply {
                    addAll(items.map(::Entry))
                    addAll(old.filterNotExpired())
                }
            while (updated.size > capacity) {
                updated.remove(updated.last())
            }
            updated
        }
    }

    override fun clear() {
        popAll()
            .forEach { it.adSource.destroy() }
    }

    override fun Entry.unwrap(): AuctionResult = result

    private suspend fun createObservers(entries: SortedSet<Entry>) {
        observers.resetAndUpdate {
            entries
                .associateWith(::createObserver)
                .toMutableMap()
        }
    }

    private fun createObserver(entry: Entry): Job =
        coroutineScope.launch {
            entry.result.adSource.adEvent.collect {
                if (it is AdEvent.Expired) remove(entry)
            }
        }

    private fun remove(entry: Entry) {
        entries.update { entrySet(*(it - entry).toTypedArray()) }
    }

    private suspend fun MutableStateFlow<MutableMap<Entry, Job>>.resetAndUpdate(function: suspend (MutableMap<Entry, Job>) -> MutableMap<Entry, Job>) {
        update {
            it.values.forEach(Job::cancel)
            function(mutableMapOf())
        }
    }

    internal class Entry(
        val result: AuctionResult,
    ) : AdBuffer.Entry {
        override val demandId: String
            get() = result.demandId

        override val price: Double
            get() = result.bidStat.price

        override val isExpired: Boolean
            get() = !result.adSource.isAdReadyToShow

        val auctionId: String
            get() = result.auctionId

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Entry

            if (auctionId != other.auctionId) return false
            if (demandId != other.demandId) return false

            return true
        }

        override fun hashCode(): Int {
            var result1 = auctionId.hashCode()
            result1 = 31 * result1 + demandId.hashCode()
            return result1
        }
    }
}

private val AuctionResult.auctionId: String
    get() = adSource.auctionId

private val AuctionResult.bidStat: BidStat
    get() = adSource.getStats()

private val AuctionResult.demandId: String
    get() = bidStat.demandId.demandId

private val AuctionResult.isExpired: Boolean
    get() = !adSource.isAdReadyToShow
