package org.bidon.sdk.ads.cache.twolevel.storage

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    fun `double check different price - removes old and re-inserts with new price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("admob", 5.0))

        val updated = makeResult("admob", 9.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(9.0)
    }

    @Test
    fun `double check different price lower - removes old and re-inserts, sorted correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val other = makeResult("dem_other", 8.0)
        storage.insert(makeResult("admob", 5.0))
        storage.insert(other)

        // update admob with lower price
        val updated = makeResult("admob", 3.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
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
    fun `duplicate by demandId - full cache, lower price, still updates (duplicate removes slot)`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0)) // full

        // Update dem2 with lower price (same demandId) → removes old, inserts new
        val updated = makeResult("dem2", 3.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `duplicate by demandId - full cache, higher price, updates correctly`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 10.0))
        storage.insert(makeResult("dem2", 5.0)) // full

        // Update dem2 with higher price
        val updated = makeResult("dem2", 8.0)
        val result = storage.insert(updated)

        assertThat(result.isInserted).isTrue()
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
}
