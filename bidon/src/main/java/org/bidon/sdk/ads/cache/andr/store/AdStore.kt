package org.bidon.sdk.ads.cache.andr.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import org.bidon.sdk.auction.models.TokenInfo
import java.util.SortedSet
import java.util.TreeSet

internal abstract class AdStore<E : AdStore.Entry>(
    val capacity: Int,
    private val comparator: Comparator<in E>,
) {
    protected val entries = MutableStateFlow<SortedSet<E>>(entrySet())

    val size: Int
        get() = entries.value.size

    fun peek(): E? = entries.evictExpiredGet().firstOrNull()

    fun pop(): E? =
        entries
            .getAndUpdate {
                val first = it.firstNotExpiredOrNull()
                if (first != null) entrySet(*(it - first).toTypedArray()) else it
            }.firstNotExpiredOrNull()

    suspend fun poll(): E = entries.mapNotNull { it.firstNotExpired() }.first()

    fun peekAll(): Set<E> = entries.evictExpiredGet()

    fun popAll(): Set<E> = entries.evictExpiredGetAndUpdate { entrySet() }

    abstract fun <T> insert(
        items: Collection<T>,
        transform: (T) -> E,
    )

    open fun remove(entry: E) {
        entries.update { entrySet(*(it - entry).toTypedArray()) }
    }

    abstract fun clear()

    protected fun entrySet(vararg elements: E): SortedSet<E> = elements.toCollection(TreeSet(comparator))

    protected fun MutableStateFlow<SortedSet<E>>.evictExpiredGet(): SortedSet<E> = updateAndGet { sortedSetOf(*it.filterNot(Entry::isExpired).toTypedArray()) }

    protected fun MutableStateFlow<SortedSet<E>>.evictExpiredGetAndUpdate(function: (SortedSet<E>) -> SortedSet<E>): SortedSet<E> {
        var filtered: SortedSet<E> = entrySet()
        getAndUpdate { current ->
            filtered = current.filterNotExpired().toCollection(TreeSet(comparator))
            function(filtered)
        }
        return filtered
    }

    protected fun Iterable<E>.filterNotExpired(): List<E> = filterNot(Entry::isExpired)

    protected fun Iterable<E>.firstNotExpired(): E = first { !it.isExpired }

    protected fun Iterable<E>.firstNotExpiredOrNull(): E? = firstOrNull { !it.isExpired }

    interface Entry : Comparable<Entry> {
        val auctionId: String

        val demandId: String

        val tokenInfo: TokenInfo?

        val price: Double

        val isExpired: Boolean

        override fun compareTo(other: Entry): Int = PriceComparator.compare(this, other)

        companion object {
            val PriceComparator: Comparator<Entry> =
                compareByDescending(Entry::price).thenBy(Entry::demandId).thenBy(Entry::auctionId)
        }
    }
}

internal fun <E : AdStore.Entry> Collection<E>.filterPrice(price: Double): Collection<E> = filter { it.price >= price }
