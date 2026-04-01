package org.bidon.sdk.ads.cache.twolevel.storage

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.RoundStatus
import org.junit.Before
import org.junit.Test

internal class FallbackCacheStorageTest {

    @Before
    fun before() {
        mockkLog()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeResult(demandId: String, price: Double): AuctionResult {
        val adSource = mockk<AdSource<*>>(relaxed = true)
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId(demandId),
            price = price,
            auctionId = null,
            roundStatus = RoundStatus.Successful,
            auctionPricefloor = 0.0,
            fillStartTs = null,
            fillFinishTs = null,
            dspSource = null,
            adUnit = null,
            tokenInfo = null,
        )
        return AuctionResult.Network(adSource = adSource, roundStatus = RoundStatus.Successful)
    }

    // -----------------------------------------------------------------------
    // Basic insert
    // -----------------------------------------------------------------------

    @Test
    fun `insert basic - empty cache, insert one item - Success and peek returns it`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val item = makeResult("admob", 5.0)

        val result = storage.insert(item)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(item)
        assertThat(storage.state.value.head).isSameInstanceAs(item)
    }

    @Test
    fun `insert multiple - items sorted descending by price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 5)
        storage.insert(makeResult("dem1", 3.0))
        storage.insert(makeResult("dem2", 7.0))
        storage.insert(makeResult("dem3", 1.0))

        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(7.0)
    }

    // -----------------------------------------------------------------------
    // Strict eviction (equal price rejected)
    // -----------------------------------------------------------------------

    @Test
    fun `insert strict eviction - full cache, new item has equal price to cheapest - Rejected CacheFull`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))

        // equal price — strict > is required
        val result = storage.insert(makeResult("dem3", 5.0))

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    @Test
    fun `insert strict eviction - full cache, new item is cheaper - Rejected CacheFull`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))

        val result = storage.insert(makeResult("dem3", 3.0))

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // Eviction when new item is more expensive
    // -----------------------------------------------------------------------

    @Test
    fun `insert eviction - full cache, new item is more expensive - Success and cheapest evicted`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        val cheap = makeResult("dem_cheap", 5.0)
        storage.insert(makeResult("dem_expensive", 10.0))
        storage.insert(cheap)

        val newcomer = makeResult("dem_new", 8.0)
        val result = storage.insert(newcomer)

        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isSameInstanceAs(cheap)
        verify(exactly = 0) { cheap.adSource.destroy() }
        // head is 10.0 (most expensive)
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `insert eviction - newly inserted item becomes head when it has highest price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 5.0))
        storage.insert(makeResult("dem2", 3.0))

        val newcomer = makeResult("dem3", 15.0)
        val result = storage.insert(newcomer)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(newcomer)
    }

    // -----------------------------------------------------------------------
    // Duplicate / double-check (same demandId)
    // -----------------------------------------------------------------------

    @Test
    fun `double check same price - updates in place - Success`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("admob", 5.0))

        val updated = makeResult("admob", 5.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
    }

    @Test
    fun `different price same demandId - both coexist (not a duplicate)`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("admob", 5.0))

        val second = makeResult("admob", 9.0)
        val result = storage.insert(second)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.size).isEqualTo(2)
        assertThat(storage.state.value.head).isSameInstanceAs(second)
    }

    @Test
    fun `different price lower same demandId - both coexist, sorted correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val other = makeResult("dem_other", 8.0)
        storage.insert(makeResult("admob", 5.0))
        storage.insert(other)

        val lower = makeResult("admob", 3.0)
        val result = storage.insert(lower)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.size).isEqualTo(3)
        // head is still other (8.0)
        assertThat(storage.state.value.head).isSameInstanceAs(other)
    }

    // -----------------------------------------------------------------------
    // popFirst
    // -----------------------------------------------------------------------

    @Test
    fun `popFirst - returns head and removes it`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val item1 = makeResult("dem1", 10.0)
        val item2 = makeResult("dem2", 5.0)
        storage.insert(item1)
        storage.insert(item2)

        val popped = storage.popFirst()

        assertThat(popped).isSameInstanceAs(item1)
        assertThat(storage.state.value.head).isSameInstanceAs(item2)
    }

    @Test
    fun `popFirst - empty storage returns null`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.popFirst()).isNull()
    }

    @Test
    fun `popFirst - single item leaves storage empty`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 5.0))

        storage.popFirst()

        assertThat(storage.state.value.head).isNull()
        assertThat(storage.state.value.head).isNull()
    }

    @Test
    fun `popFirst - sequential pops return items in descending price order`() = runTest {
        val storage = FallbackCacheStorage(capacity = 5)
        storage.insert(makeResult("dem1", 3.0))
        storage.insert(makeResult("dem2", 10.0))
        storage.insert(makeResult("dem3", 7.0))

        val first = storage.popFirst()!!
        val second = storage.popFirst()!!
        val third = storage.popFirst()!!

        assertThat(first.adSource.getStats().price).isEqualTo(10.0)
        assertThat(second.adSource.getStats().price).isEqualTo(7.0)
        assertThat(third.adSource.getStats().price).isEqualTo(3.0)
        assertThat(storage.popFirst()).isNull()
    }

    // -----------------------------------------------------------------------
    // peek
    // -----------------------------------------------------------------------

    @Test
    fun `peek - returns head without removing it`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val item = makeResult("dem1", 5.0)
        storage.insert(item)

        val first = storage.state.value.head
        val second = storage.state.value.head

        assertThat(first).isSameInstanceAs(item)
        assertThat(second).isSameInstanceAs(item)
    }

    @Test
    fun `peek - empty storage returns null`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.state.value.head).isNull()
    }

    // -----------------------------------------------------------------------
    // peekSnapshot (non-suspend)
    // -----------------------------------------------------------------------

    @Test
    fun `peekSnapshot - reflects head after insert`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val item = makeResult("dem1", 5.0)
        storage.insert(item)

        assertThat(storage.state.value.head).isSameInstanceAs(item)
    }

    @Test
    fun `peekSnapshot - null before any insert`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.state.value.head).isNull()
    }

    // -----------------------------------------------------------------------
    // Capacity boundary
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 1 - insert two items where second is more expensive - evicts first`() = runTest {
        val storage = FallbackCacheStorage(capacity = 1)
        val cheap = makeResult("dem1", 5.0)
        storage.insert(cheap)

        val expensive = makeResult("dem2", 10.0)
        val result = storage.insert(expensive)

        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isSameInstanceAs(cheap)
        // Storage no longer destroys evicted items — caller is responsible
        verify(exactly = 0) { cheap.adSource.destroy() }
        assertThat(storage.state.value.head).isSameInstanceAs(expensive)
    }

    @Test
    fun `capacity 1 - insert two items where second is cheaper - rejected`() = runTest {
        val storage = FallbackCacheStorage(capacity = 1)
        storage.insert(makeResult("dem1", 10.0))

        val result = storage.insert(makeResult("dem2", 5.0))

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // Disabled cache (capacity=0)
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 0 - isDisabled returns true`() {
        val storage = FallbackCacheStorage(capacity = 0)
        assertThat(storage.isDisabled).isTrue()
    }

    @Test
    fun `capacity 0 - insert rejected`() = runTest {
        val storage = FallbackCacheStorage(capacity = 0)
        val result = storage.insert(makeResult("admob", 10.0))
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    @Test
    fun `capacity 0 - popFirst returns null`() = runTest {
        val storage = FallbackCacheStorage(capacity = 0)
        assertThat(storage.popFirst()).isNull()
    }

    @Test
    fun `capacity 0 - state isFull is true, hasContent is false`() {
        val storage = FallbackCacheStorage(capacity = 0)
        assertThat(storage.state.value.isFull).isTrue()
        assertThat(storage.state.value.hasContent).isFalse()
    }

    @Test
    fun `positive capacity - isDisabled returns false`() {
        val storage = FallbackCacheStorage(capacity = 3)
        assertThat(storage.isDisabled).isFalse()
    }

    // -----------------------------------------------------------------------
    // Eviction chain (sequential evictions)
    // -----------------------------------------------------------------------

    @Test
    fun `eviction chain - multiple sequential evictions with increasing prices`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        val bid1 = makeResult("dem1", 3.0)
        val bid2 = makeResult("dem2", 1.0)
        storage.insert(bid1)
        storage.insert(bid2) // full: [3, 1]

        // First eviction: 4 > 1 → evict dem2
        val r1 = storage.insert(makeResult("dem3", 4.0))
        assertThat(r1.isInserted).isTrue()
        assertThat((r1 as InsertResult.Success).evicted).isSameInstanceAs(bid2)

        // Second eviction: 5 > 3 → evict dem1
        val r2 = storage.insert(makeResult("dem4", 5.0))
        assertThat(r2.isInserted).isTrue()
        assertThat((r2 as InsertResult.Success).evicted).isSameInstanceAs(bid1)

        // Now: [5, 4]
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(5.0)
    }

    // -----------------------------------------------------------------------
    // State tracking
    // -----------------------------------------------------------------------

    @Test
    fun `state cheapestPrice - tracks cheapest item correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.state.value.cheapestPrice).isNull()

        storage.insert(makeResult("dem1", 10.0))
        assertThat(storage.state.value.cheapestPrice).isEqualTo(10.0)

        storage.insert(makeResult("dem2", 5.0))
        assertThat(storage.state.value.cheapestPrice).isEqualTo(5.0)

        storage.insert(makeResult("dem3", 8.0))
        assertThat(storage.state.value.cheapestPrice).isEqualTo(5.0) // still 5.0
    }

    @Test
    fun `state isFull - transitions correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)

        assertThat(storage.state.value.isFull).isFalse()

        storage.insert(makeResult("dem1", 10.0))
        assertThat(storage.state.value.isFull).isFalse()

        storage.insert(makeResult("dem2", 5.0))
        assertThat(storage.state.value.isFull).isTrue()
    }

    @Test
    fun `state size - tracks correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 5)

        assertThat(storage.state.value.size).isEqualTo(0)

        storage.insert(makeResult("dem1", 10.0))
        assertThat(storage.state.value.size).isEqualTo(1)

        storage.insert(makeResult("dem2", 5.0))
        assertThat(storage.state.value.size).isEqualTo(2)

        storage.popFirst()
        assertThat(storage.state.value.size).isEqualTo(1)
    }

    @Test
    fun `state hasContent - false when empty, true with items`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.state.value.hasContent).isFalse()

        storage.insert(makeResult("dem1", 5.0))
        assertThat(storage.state.value.hasContent).isTrue()

        storage.popFirst()
        assertThat(storage.state.value.hasContent).isFalse()
    }

    // -----------------------------------------------------------------------
    // Insert after pop (capacity freed)
    // -----------------------------------------------------------------------

    @Test
    fun `insert after pop - was full, now has space, accepts without eviction`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))
        assertThat(storage.state.value.isFull).isTrue()

        storage.popFirst() // removes 10.0
        assertThat(storage.state.value.isFull).isFalse()

        val result = storage.insert(makeResult("dem3", 3.0))
        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isNull() // no eviction needed
    }

    // -----------------------------------------------------------------------
    // Duplicate edge case: demandId match with price below eviction threshold
    // -----------------------------------------------------------------------

    @Test
    fun `same demandId different price - full cache, lower price, eviction applies`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0)) // full

        // dem2 with lower price is NOT a duplicate (different price) → eviction rules apply
        val lower = makeResult("dem2", 3.0)
        val result = storage.insert(lower)

        // 3.0 <= cheapest (5.0) → rejected
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    @Test
    fun `same demandId different price - full cache, higher price, evicts cheapest`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0)) // full

        // dem2 with higher price is NOT a duplicate → 8.0 > 5.0 → evicts cheapest
        val higher = makeResult("dem2", 8.0)
        val result = storage.insert(higher)

        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isNotNull()
        assertThat(storage.state.value.size).isEqualTo(2)
    }

    @Test
    fun `cheapestPrice updates after eviction`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 5.0))
        storage.insert(makeResult("dem2", 3.0)) // cheapest = 3.0

        assertThat(storage.state.value.cheapestPrice).isEqualTo(3.0)

        // Evict 3.0, insert 7.0
        storage.insert(makeResult("dem3", 7.0))
        assertThat(storage.state.value.cheapestPrice).isEqualTo(5.0) // new cheapest
    }

    // -----------------------------------------------------------------------
    // Negative capacity
    // -----------------------------------------------------------------------

    @Test
    fun `negative capacity - treated as disabled`() {
        val storage = FallbackCacheStorage(capacity = -1)
        assertThat(storage.isDisabled).isTrue()
        assertThat(storage.state.value.isFull).isTrue()
    }

    @Test
    fun `negative capacity - insert rejected`() = runTest {
        val storage = FallbackCacheStorage(capacity = -1)
        val result = storage.insert(makeResult("admob", 10.0))
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // Capacity 10 (max config range)
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 10 - fills completely and sorts correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 10)
        val prices = listOf(5.0, 3.0, 8.0, 1.0, 9.0, 2.0, 7.0, 4.0, 10.0, 6.0)
        for ((i, price) in prices.withIndex()) {
            storage.insert(makeResult("dem$i", price))
        }

        assertThat(storage.state.value.isFull).isTrue()
        assertThat(storage.state.value.size).isEqualTo(10)
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
        assertThat(storage.state.value.cheapestPrice).isEqualTo(1.0)
    }

    @Test
    fun `capacity 10 - pop all in descending order`() = runTest {
        val storage = FallbackCacheStorage(capacity = 10)
        for (i in 1..10) {
            storage.insert(makeResult("dem$i", i.toDouble()))
        }

        val popped = mutableListOf<Double>()
        while (true) {
            val item = storage.popFirst() ?: break
            popped.add(item.adSource.getStats().price)
        }

        assertThat(popped).isEqualTo((10 downTo 1).map { it.toDouble() })
    }

    // -----------------------------------------------------------------------
    // Insert order independence
    // -----------------------------------------------------------------------

    @Test
    fun `insert order does not affect final sort - ascending`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 1.0))
        storage.insert(makeResult("dem2", 5.0))
        storage.insert(makeResult("dem3", 10.0))

        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
        assertThat(storage.state.value.cheapestPrice).isEqualTo(1.0)
    }

    @Test
    fun `insert order does not affect final sort - descending`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))
        storage.insert(makeResult("dem3", 1.0))

        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
        assertThat(storage.state.value.cheapestPrice).isEqualTo(1.0)
    }

    @Test
    fun `insert order does not affect final sort - random`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 5.0))
        storage.insert(makeResult("dem2", 1.0))
        storage.insert(makeResult("dem3", 10.0))

        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
        assertThat(storage.state.value.cheapestPrice).isEqualTo(1.0)
    }

    // -----------------------------------------------------------------------
    // Full drain and refill
    // -----------------------------------------------------------------------

    @Test
    fun `full drain and refill - cache resets properly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)

        // Fill
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))
        assertThat(storage.state.value.isFull).isTrue()

        // Drain
        storage.popFirst()
        storage.popFirst()
        assertThat(storage.state.value.size).isEqualTo(0)
        assertThat(storage.state.value.hasContent).isFalse()
        assertThat(storage.state.value.cheapestPrice).isNull()
        assertThat(storage.state.value.isFull).isFalse()

        // Refill
        val r = storage.insert(makeResult("dem3", 1.0))
        assertThat(r.isInserted).isTrue()
        assertThat((r as InsertResult.Success).evicted).isNull()
    }

    // -----------------------------------------------------------------------
    // Concurrent operations
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent inserts - all complete without exception`() = runTest {
        val storage = FallbackCacheStorage(capacity = 50)

        val results = (1..50).map { i ->
            async {
                storage.insert(makeResult("dem$i", i.toDouble()))
            }
        }.awaitAll()

        assertThat(results.all { it.isInserted }).isTrue()
        assertThat(storage.state.value.size).isEqualTo(50)
    }

    @Test
    fun `concurrent pops - no duplicates returned`() = runTest {
        val storage = FallbackCacheStorage(capacity = 10)
        for (i in 1..10) {
            storage.insert(makeResult("dem$i", i.toDouble()))
        }

        val popped = (1..10).map {
            async { storage.popFirst() }
        }.awaitAll()

        val nonNull = popped.filterNotNull()
        assertThat(nonNull).hasSize(10)
        // All unique prices
        val prices = nonNull.map { it.adSource.getStats().price }.toSet()
        assertThat(prices).hasSize(10)
    }

    // -----------------------------------------------------------------------
    // Price edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `price zero - accepted into empty cache`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val result = storage.insert(makeResult("dem1", 0.0))
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `price zero - not evicted by another zero (equal, not strictly greater)`() = runTest {
        val storage = FallbackCacheStorage(capacity = 1)
        storage.insert(makeResult("dem1", 0.0))

        val result = storage.insert(makeResult("dem2", 0.0))
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    @Test
    fun `very small price difference - eviction still works`() = runTest {
        val storage = FallbackCacheStorage(capacity = 1)
        val cheap = makeResult("dem1", 1.0)
        storage.insert(cheap)

        val slightlyMore = makeResult("dem2", 1.0000001)
        val result = storage.insert(slightlyMore)

        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isSameInstanceAs(cheap)
    }

    // -----------------------------------------------------------------------
    // Eviction returns correct evicted item
    // -----------------------------------------------------------------------

    @Test
    fun `eviction - evicts the actual cheapest among multiple items`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))
        val cheapest = makeResult("dem3", 2.0)
        storage.insert(cheapest) // full: [10, 5, 2]

        val result = storage.insert(makeResult("dem4", 3.0)) // 3 > 2 → evict dem3
        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isSameInstanceAs(cheapest)
    }

    @Test
    fun `eviction result has null evicted when not full`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val result = storage.insert(makeResult("dem1", 10.0))
        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isNull()
    }

    // -----------------------------------------------------------------------
    // FallbackSnapshot data class
    // -----------------------------------------------------------------------

    @Test
    fun `FallbackSnapshot hasContent - derived from head`() {
        val withHead = FallbackSnapshot(head = mockk(relaxed = true), isFull = false, cheapestPrice = 5.0, size = 1)
        assertThat(withHead.hasContent).isTrue()

        val noHead = FallbackSnapshot(head = null, isFull = false, cheapestPrice = null, size = 0)
        assertThat(noHead.hasContent).isFalse()
    }

    @Test
    fun `FallbackSnapshot defaults - initial state matches empty`() {
        val storage = FallbackCacheStorage(capacity = 3)
        val snap = storage.state.value
        assertThat(snap.head).isNull()
        assertThat(snap.isFull).isFalse()
        assertThat(snap.cheapestPrice).isNull()
        assertThat(snap.size).isEqualTo(0)
        assertThat(snap.hasContent).isFalse()
    }

    // -----------------------------------------------------------------------
    // Duplicate edge: same demandId appears in full cache, frees slot
    // -----------------------------------------------------------------------

    @Test
    fun `same demandId same price in full cache - duplicate removed, reinserted`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0)) // full

        // dem1 same price: duplicate (same demandId + same price) → removes old, frees slot, inserts new
        val updated = makeResult("dem1", 10.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
        assertThat((result as InsertResult.Success).evicted).isNull()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
        assertThat(storage.state.value.size).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // Multiple same-price items from different demands
    // -----------------------------------------------------------------------

    @Test
    fun `same price different demands - all coexist`() = runTest {
        val storage = FallbackCacheStorage(capacity = 5)
        storage.insert(makeResult("admob", 5.0))
        storage.insert(makeResult("applovin", 5.0))
        storage.insert(makeResult("unity", 5.0))

        assertThat(storage.state.value.size).isEqualTo(3)
        assertThat(storage.state.value.cheapestPrice).isEqualTo(5.0)
    }

    @Test
    fun `same price all items - eviction rejected (equal not gt)`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 5.0))
        storage.insert(makeResult("dem2", 5.0))

        val result = storage.insert(makeResult("dem3", 5.0))
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // cheapestPrice after pop
    // -----------------------------------------------------------------------

    @Test
    fun `cheapestPrice updates after pop removes cheapest`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0))
        storage.insert(makeResult("dem3", 2.0))

        assertThat(storage.state.value.cheapestPrice).isEqualTo(2.0)

        // Pop removes head (10.0), cheapest stays 2.0
        storage.popFirst()
        assertThat(storage.state.value.cheapestPrice).isEqualTo(2.0)

        // Pop removes 5.0, cheapest stays 2.0
        storage.popFirst()
        assertThat(storage.state.value.cheapestPrice).isEqualTo(2.0)

        // Pop removes last (2.0), cheapest = null
        storage.popFirst()
        assertThat(storage.state.value.cheapestPrice).isNull()
    }
}
