package org.bidon.sdk.ads.cache.andr.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidStat
import java.util.SortedSet
import kotlin.coroutines.CoroutineContext

internal class AuctionResultStore(
    private val tag: String,
    coroutineContext: CoroutineContext,
    capacity: Int,
    private val adType: AdType,
) : AdStore<AuctionResultStore.Entry>(capacity, AdStore.Entry.PriceComparator) {
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

    override fun <T> insert(
        items: Collection<T>,
        transform: (T) -> Entry
    ) {
        val evicted = mutableListOf<Entry>()
        entries.update { old ->
            evicted.clear()
            val oldExpired = old.filterNotExpired().toSet()
            val updated =
                entrySet().apply {
                    addAll(items.map(transform))
                    addAll(oldExpired)
                }
            evicted.addAll(old - oldExpired)
            // Dedup singleton adapters: keep highest price per demandId
            val singletons = singletonDemandIds[adType].orEmpty()
            if (singletons.isNotEmpty()) {
                val seen = mutableSetOf<String>()
                val toRemove = mutableListOf<Entry>()
                for (entry in updated) { // TreeSet iterates highest price first (PriceComparator)
                    if (entry.demandId in singletons && !seen.add(entry.demandId)) {
                        toRemove.add(entry)
                    }
                }
                updated.removeAll(toRemove.toSet())
                evicted.addAll(toRemove)
            }
            while (updated.size > capacity) {
                val last = updated.last()
                updated.remove(last)
                evicted.add(last)
            }
            updated
        }
        evicted.forEach { it.auctionResult.adSource.destroy() }
        logInfo(
            tag,
            "AuctionResultStore.insert: +${items.size}, evicted=${evicted.size}, total=${entries.value.size}"
        )
    }

    override fun remove(entry: Entry) {
        super.remove(entry)
        entry.auctionResult.adSource.destroy()
        logInfo(tag, "AuctionResultStore.remove: ${entry.demandId}:${entry.price}")
    }

    override fun clear() {
        val count = entries.value.size
        entries.getAndUpdate { entrySet() }.forEach { it.auctionResult.adSource.destroy() }
        logInfo(tag, "AuctionResultStore.clear: removed $count entries")
    }

    private suspend fun createObservers(entries: SortedSet<Entry>) {
        observers.resetAndUpdate {
            entries.associateWith(::createObserver).toMutableMap()
        }
    }

    private fun createObserver(entry: Entry): Job =
        coroutineScope.launch {
            entry.auctionResult.adSource.adEvent.collect {
                if (it is AdEvent.Expired) {
                    logInfo(tag, "AuctionResultStore.expired: ${entry.demandId}:${entry.price}")
                    remove(entry)
                }
            }
        }

    private suspend fun MutableStateFlow<MutableMap<Entry, Job>>.resetAndUpdate(function: suspend (MutableMap<Entry, Job>) -> MutableMap<Entry, Job>) {
        update {
            it.values.forEach(Job::cancel)
            function(mutableMapOf())
        }
    }

    companion object {
        val singletonDemandIds: Map<AdType, Set<String>> =
            mapOf(
                AdType.Interstitial to setOf("unityads", "ironsource", "yandex", "fyber"),
                AdType.Banner to setOf("ironsource", "fyber"),
                AdType.Rewarded to setOf("unityads", "ironsource", "yandex", "fyber"),
            )
    }

    internal class Entry(
        val auctionResult: AuctionResult,
        val auctionInfo: AuctionInfo,
    ) : AdStore.Entry {
        override val auctionId: String
            get() = auctionResult.auctionId

        override val demandId: String
            get() = auctionResult.demandId

        override val tokenInfo: TokenInfo?
            get() = null

        override val price: Double
            get() = auctionResult.bidStat.price

        override val isExpired: Boolean
            get() = !auctionResult.adSource.isAdReadyToShow

        fun unwrap(): AuctionResult = auctionResult

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

internal fun Collection<AuctionResultStore.Entry>.asString(): String =
    buildString {
        append("(${this@asString.size}) ")
        append(
            joinToString {
                val stats = it.auctionResult.adSource.getStats()
                "${stats.demandId.demandId}:${stats.price}"
            }
        )
    }