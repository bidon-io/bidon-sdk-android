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

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(item)
        assertThat(storage.peekSnapshot()).isSameInstanceAs(item)
    }

    @Test
    fun `insert multiple - items sorted descending by price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 5)
        storage.insert(makeResult("dem1", 3.0))
        storage.insert(makeResult("dem2", 7.0))
        storage.insert(makeResult("dem3", 1.0))

        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(7.0)
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

        assertThat(result).isEqualTo(InsertResult.Success)
        verify { cheap.adSource.destroy() }
        // head is 10.0 (most expensive)
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `insert eviction - newly inserted item becomes head when it has highest price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 2)
        storage.insert(makeResult("dem1", 5.0))
        storage.insert(makeResult("dem2", 3.0))

        val newcomer = makeResult("dem3", 15.0)
        val result = storage.insert(newcomer)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(newcomer)
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

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(updated)
    }

    @Test
    fun `double check different price - removes old and re-inserts with new price`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        storage.insert(makeResult("admob", 5.0))

        val updated = makeResult("admob", 9.0)
        val result = storage.insert(updated)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(updated)
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(9.0)
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

        assertThat(result).isEqualTo(InsertResult.Success)
        // head is still other (8.0)
        assertThat(storage.peek()).isSameInstanceAs(other)
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
        assertThat(storage.peek()).isSameInstanceAs(item2)
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

        assertThat(storage.peek()).isNull()
        assertThat(storage.peekSnapshot()).isNull()
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

        val first = storage.peek()
        val second = storage.peek()

        assertThat(first).isSameInstanceAs(item)
        assertThat(second).isSameInstanceAs(item)
    }

    @Test
    fun `peek - empty storage returns null`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.peek()).isNull()
    }

    // -----------------------------------------------------------------------
    // peekSnapshot (non-suspend)
    // -----------------------------------------------------------------------

    @Test
    fun `peekSnapshot - reflects head after insert`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)
        val item = makeResult("dem1", 5.0)
        storage.insert(item)

        assertThat(storage.peekSnapshot()).isSameInstanceAs(item)
    }

    @Test
    fun `peekSnapshot - null before any insert`() = runTest {
        val storage = FallbackCacheStorage(capacity = 3)

        assertThat(storage.peekSnapshot()).isNull()
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

        assertThat(result).isEqualTo(InsertResult.Success)
        verify { cheap.adSource.destroy() }
        assertThat(storage.peek()).isSameInstanceAs(expensive)
    }

    @Test
    fun `capacity 1 - insert two items where second is cheaper - rejected`() = runTest {
        val storage = FallbackCacheStorage(capacity = 1)
        storage.insert(makeResult("dem1", 10.0))

        val result = storage.insert(makeResult("dem2", 5.0))

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }
}
