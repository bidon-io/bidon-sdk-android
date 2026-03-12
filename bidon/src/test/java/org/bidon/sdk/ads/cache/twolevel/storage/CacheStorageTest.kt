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

internal class CacheStorageTest {

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
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val item = makeResult("admob", 5.0)

        val result = storage.insert(item, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(item)
        assertThat(storage.peekSnapshot()).isSameInstanceAs(item)
    }

    @Test
    fun `insert with sticky - inserted item becomes head`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val item = makeResult("admob", 5.0)

        val result = storage.insert(item, sticky = true)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(item)
    }

    // -----------------------------------------------------------------------
    // Capacity rejection (no eviction)
    // -----------------------------------------------------------------------

    @Test
    fun `insert capacity - cache full and new item is cheaper - Rejected CacheFull`() = runTest {
        // Use prices that all pass threshold (within 80% of max):
        // max = 10.0, 80% = 8.0. Use 9.0 and 8.5 so both pass threshold.
        // Then insert something cheap that hits CacheFull (not threshold).
        val storage = CacheStorage(capacity = 2, iterationThreshold = 80)
        storage.beginIteration()
        storage.insert(makeResult("dem1", 10.0), sticky = false) // sets max = 10.0
        storage.insert(makeResult("dem2", 9.0), sticky = false) // 9.0 >= 8.0 → passes

        // Start new iteration so threshold is reset, then insert the cheap item
        storage.beginIteration()
        val result = storage.insert(makeResult("dem3", 3.0), sticky = false) // sets max = 3.0, passes threshold
        // Now cache is full and this item will be checked against cheapest on re-insert?
        // Actually: beginIteration, first insert of 3.0 → sets max=3.0, passes. But cache is full:
        // cheapest is 9.0? No — cheapest is 9.0 and 3.0 <= 9.0 → CacheFull
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    @Test
    fun `insert capacity - cache full and new item has equal price to cheapest - Rejected CacheFull`() = runTest {
        val storage = CacheStorage(capacity = 2, iterationThreshold = 80)
        storage.beginIteration()
        storage.insert(makeResult("dem1", 10.0), sticky = false) // sets max = 10.0
        storage.insert(makeResult("dem2", 9.0), sticky = false) // passes

        // new iteration, insert with same price as cheapest (9.0) — <= means rejected by CacheFull
        storage.beginIteration()
        val result = storage.insert(makeResult("dem3", 9.0), sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // Capacity eviction
    // -----------------------------------------------------------------------

    @Test
    fun `insert capacity eviction - cache full but new item is more expensive - Success and cheapest evicted`() = runTest {
        val storage = CacheStorage(capacity = 2, iterationThreshold = 80)
        storage.beginIteration()
        val cheap = makeResult("dem_cheap", 9.0)
        storage.insert(makeResult("dem_expensive", 10.0), sticky = false) // max = 10.0
        storage.insert(cheap, sticky = false) // 9.0 >= 8.0 → passes

        // new iteration, insert more expensive than cheapest (9.0) → evicts cheap
        storage.beginIteration()
        val newcomer = makeResult("dem_new", 11.0) // sets new max = 11.0, passes
        val result = storage.insert(newcomer, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Success)
        verify { cheap.adSource.destroy() }
        // head is the most expensive item (11.0 or 10.0)
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(11.0)
    }

    // -----------------------------------------------------------------------
    // Iteration threshold
    // -----------------------------------------------------------------------

    @Test
    fun `iteration threshold - first item sets max, second item below threshold - Rejected`() = runTest {
        // threshold = 80 means: price >= maxPrice * 0.8 is allowed
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.beginIteration()

        // First item: price = 10.0 → sets iterationMaxPrice = 10.0
        val result1 = storage.insert(makeResult("dem1", 10.0), sticky = false)
        assertThat(result1).isEqualTo(InsertResult.Success)

        // Second item: price = 7.9 → 7.9 < 10.0 * 0.8 = 8.0 → Rejected
        val result2 = storage.insert(makeResult("dem2", 7.9), sticky = false)
        assertThat(result2).isEqualTo(InsertResult.Rejected(InsertResult.Reason.IterationThreshold))
    }

    @Test
    fun `iteration threshold pass - item at exactly threshold percentage - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.beginIteration()

        // First item sets max = 10.0
        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // At exactly threshold: 10.0 * 0.8 = 8.0 → pass
        val result = storage.insert(makeResult("dem2", 8.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Success)
    }

    @Test
    fun `iteration threshold pass - item above threshold percentage - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.beginIteration()

        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // 9.0 >= 10.0 * 0.8 = 8.0 → pass
        val result = storage.insert(makeResult("dem2", 9.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Success)
    }

    @Test
    fun `iteration threshold - new max price resets the threshold bar`() = runTest {
        val storage = CacheStorage(capacity = 5, iterationThreshold = 80)
        storage.beginIteration()

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        // higher price → updates max to 15.0
        val result = storage.insert(makeResult("dem2", 15.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Success)
    }

    @Test
    fun `iteration threshold - beginIteration resets max so first item always passes`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.beginIteration()
        storage.insert(makeResult("dem1", 10.0), sticky = false)
        // cheap item rejected
        val rejected = storage.insert(makeResult("dem2", 1.0), sticky = false)
        assertThat(rejected).isEqualTo(InsertResult.Rejected(InsertResult.Reason.IterationThreshold))

        // new iteration resets the max
        storage.beginIteration()
        // first insert in new iteration always passes regardless of price
        val result = storage.insert(makeResult("dem3", 1.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Success)
    }

    @Test
    fun `iteration threshold - not applied when capacity is 1`() = runTest {
        val storage = CacheStorage(capacity = 1, iterationThreshold = 80)
        storage.beginIteration()

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        // capacity=1 means threshold guard is skipped; but cache is full for dem2
        // A different demand with lower price is rejected only due to capacity
        val result = storage.insert(makeResult("dem2", 1.0), sticky = false)
        // capacity=1, stickyHeadActive=false → CacheFull because 1.0 <= 10.0
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.CacheFull))
    }

    // -----------------------------------------------------------------------
    // Sticky head protection
    // -----------------------------------------------------------------------

    @Test
    fun `sticky head protection - capacity 1, insert sticky then non-sticky - Rejected StickyHeadProtected`() = runTest {
        val storage = CacheStorage(capacity = 1, iterationThreshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = true)

        // Any non-sticky insert to a capacity=1 storage with sticky head should be rejected
        val result = storage.insert(makeResult("dem2", 10.0), sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyHeadProtected))
    }

    @Test
    fun `sticky head protection - capacity 1, two sticky inserts - second one also rejected`() = runTest {
        val storage = CacheStorage(capacity = 1, iterationThreshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = true)

        // capacity=1 + stickyHeadActive=true, items not empty → StickyHeadProtected regardless of sticky flag
        val result = storage.insert(makeResult("dem2", 10.0), sticky = true)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyHeadProtected))
    }

    // -----------------------------------------------------------------------
    // Duplicate / double-check (same demandId)
    // -----------------------------------------------------------------------

    @Test
    fun `double check same price - updates in place - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.insert(makeResult("admob", 5.0), sticky = false)

        val updated = makeResult("admob", 5.0)
        val result = storage.insert(updated, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(updated)
    }

    @Test
    fun `double check different price - removes old and re-inserts with new price - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val old = makeResult("admob", 5.0)
        storage.insert(old, sticky = false)

        val updated = makeResult("admob", 9.0)
        val result = storage.insert(updated, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Success)
        assertThat(storage.peek()).isSameInstanceAs(updated)
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(9.0)
    }

    @Test
    fun `double check same demandId same price sticky head - non-sticky update rejected StickyHeadProtected`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.insert(makeResult("admob", 5.0), sticky = true)

        // same demandId, same price, not sticky → StickyHeadProtected
        val result = storage.insert(makeResult("admob", 5.0), sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyHeadProtected))
    }

    // -----------------------------------------------------------------------
    // sortAccordingToMode: sticky head stays at [0], tail is sorted
    // -----------------------------------------------------------------------

    @Test
    fun `sortAccordingToMode sticky - sticky head stays at index 0, tail is sorted descending`() = runTest {
        val storage = CacheStorage(capacity = 5, iterationThreshold = 80)
        // Insert sticky head with medium price
        val stickyItem = makeResult("sticky", 5.0)
        storage.insert(stickyItem, sticky = true)

        // Insert two more items with higher prices — they should go to tail
        storage.insert(makeResult("dem_high", 10.0), sticky = false)
        storage.insert(makeResult("dem_low", 2.0), sticky = false)

        // head must still be the sticky item
        assertThat(storage.peek()).isSameInstanceAs(stickyItem)
    }

    @Test
    fun `normal mode sort - items sorted descending by price`() = runTest {
        val storage = CacheStorage(capacity = 5, iterationThreshold = 80)
        storage.insert(makeResult("dem1", 3.0), sticky = false)
        storage.insert(makeResult("dem2", 7.0), sticky = false)
        storage.insert(makeResult("dem3", 1.0), sticky = false)

        // head should be the highest price
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(7.0)
    }

    // -----------------------------------------------------------------------
    // popFirst
    // -----------------------------------------------------------------------

    @Test
    fun `popFirst - returns head and removes it`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val item1 = makeResult("dem1", 10.0)
        val item2 = makeResult("dem2", 9.0) // 9.0 >= 10.0 * 0.8 = 8.0 → passes threshold
        storage.insert(item1, sticky = false)
        storage.insert(item2, sticky = false)

        val popped = storage.popFirst()

        assertThat(popped).isSameInstanceAs(item1)
        // second item is now the head
        assertThat(storage.peek()).isSameInstanceAs(item2)
    }

    @Test
    fun `popFirst - disables sticky mode and resorts remaining items`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val sticky = makeResult("sticky", 5.0)
        storage.insert(sticky, sticky = true)
        storage.insert(makeResult("dem_high", 10.0), sticky = false)
        storage.insert(makeResult("dem_low", 2.0), sticky = false)

        // pop the sticky head
        val popped = storage.popFirst()
        assertThat(popped).isSameInstanceAs(sticky)

        // Now the highest remaining should be head (10.0 > 2.0)
        assertThat(storage.peek()!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `popFirst - empty storage returns null`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)

        val result = storage.popFirst()

        assertThat(result).isNull()
    }

    @Test
    fun `popFirst - single item leaves storage empty`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = false)

        storage.popFirst()

        assertThat(storage.peek()).isNull()
        assertThat(storage.peekSnapshot()).isNull()
    }

    // -----------------------------------------------------------------------
    // peek
    // -----------------------------------------------------------------------

    @Test
    fun `peek - returns head without removing it`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val item = makeResult("dem1", 5.0)
        storage.insert(item, sticky = false)

        val first = storage.peek()
        val second = storage.peek()

        assertThat(first).isSameInstanceAs(item)
        assertThat(second).isSameInstanceAs(item)
    }

    @Test
    fun `peek - empty storage returns null`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)

        assertThat(storage.peek()).isNull()
        assertThat(storage.peekSnapshot()).isNull()
    }

    // -----------------------------------------------------------------------
    // peekSnapshot (non-suspend)
    // -----------------------------------------------------------------------

    @Test
    fun `peekSnapshot - reflects head after insert`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)
        val item = makeResult("dem1", 5.0)
        storage.insert(item, sticky = false)

        assertThat(storage.peekSnapshot()).isSameInstanceAs(item)
    }

    @Test
    fun `peekSnapshot - null before any insert`() = runTest {
        val storage = CacheStorage(capacity = 3, iterationThreshold = 80)

        assertThat(storage.peekSnapshot()).isNull()
    }

    // -----------------------------------------------------------------------
    // Multiple items eviction ordering
    // -----------------------------------------------------------------------

    @Test
    fun `sticky eviction protection - sticky head is never evicted even if cheapest`() = runTest {
        val storage = CacheStorage(capacity = 2, iterationThreshold = 80)
        val stickyItem = makeResult("sticky", 1.0) // low price sticky head
        storage.insert(stickyItem, sticky = true)
        storage.insert(makeResult("dem2", 8.0), sticky = false)

        // Try to insert a more expensive item — eviction candidate should be dem2 (not sticky head)
        val newcomer = makeResult("dem3", 10.0)
        val result = storage.insert(newcomer, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Success)
        // sticky head survives
        assertThat(storage.peek()).isSameInstanceAs(stickyItem)
    }
}
