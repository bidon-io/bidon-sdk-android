package org.bidon.sdk.ads.cache.impl.andr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.SortedSet
import java.util.TreeSet

internal abstract class AdBuffer<T, E : AdBuffer.Entry>(
    val capacity: Int,
    private val comparator: Comparator<in E>,
) {
    protected val entries = MutableStateFlow<SortedSet<E>>(entrySet())

    val size: Int
        get() = entries.value.size

    val peekPrice: Double?
        get() = entries.value.firstNotExpiredOrNull()?.price

    val demandIds: Set<String>
        get() = entries.value.mapTo(mutableSetOf(), Entry::demandId)

    fun peek(): T? =
        entries.value
            .firstNotExpiredOrNull()
            ?.unwrap()

    fun pop(): T? =
        entries
            .getAndUpdate { old ->
                val first = old.firstNotExpiredOrNull()
                if (first != null) entrySet(*(old - first).toTypedArray()) else old
            }.firstNotExpiredOrNull()
            ?.unwrap()

    fun poll(): T = pop()!!

    fun peekAll(): Set<T> =
        entries.value
            .map { it.unwrap() }
            .toSet()

    fun popAll(): Set<T> =
        entries
            .getAndUpdate { entrySet() }
            .map { it.unwrap() }
            .toSet()

    abstract fun insert(vararg items: T)

    abstract fun clear()

    protected abstract fun E.unwrap(): T

    protected fun entrySet(vararg elements: E): SortedSet<E> = elements.toCollection(TreeSet(comparator))

    protected fun Iterable<E>.filterNotExpired(): List<E> = filterNot(Entry::isExpired)

    protected fun Iterable<E>.firstNotExpiredOrNull(): E? = firstOrNull { !it.isExpired }

    interface Entry : Comparable<Entry> {
        val demandId: String

        val price: Double

        val isExpired: Boolean

        override fun compareTo(other: Entry): Int = PriceComparator.compare(this, other)

        companion object {
            val PriceComparator: Comparator<Entry> =
                compareByDescending(Entry::price)
                    .thenBy(Entry::demandId)
        }
    }
}
