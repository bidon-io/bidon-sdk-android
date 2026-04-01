package org.bidon.sdk.ads.cache.twolevel.storage

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
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
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val item = makeResult("admob", 5.0)

        val result = storage.insert(item, sticky = false)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(item)
        assertThat(storage.state.value.head).isSameInstanceAs(item)
    }

    @Test
    fun `insert with sticky - inserted item becomes head`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val item = makeResult("admob", 5.0)

        val result = storage.insert(item, sticky = true)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(item)
    }

    // -----------------------------------------------------------------------
    // Threshold rejection (Main has no eviction — caller checks !isFull)
    // -----------------------------------------------------------------------

    @Test
    fun `insert threshold - cache full, cheap item rejected by threshold`() = runTest {
        // max = 10.0, bar = 8.0. Cache full (2/2). Cheap item fails threshold.
        val storage = CacheStorage(capacity = 2, threshold = 80)
        storage.insert(makeResult("dem1", 10.0), sticky = false)
        storage.insert(makeResult("dem2", 9.0), sticky = false) // 9.0 >= 8.0

        val result = storage.insert(makeResult("dem3", 3.0), sticky = false)
        // 3.0 < 8.0 → threshold rejection
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    // -----------------------------------------------------------------------
    // Iteration threshold
    // -----------------------------------------------------------------------

    @Test
    fun `threshold - first item sets max, second item below threshold - Rejected`() = runTest {
        // threshold = 80 means: price >= maxPrice * 0.8 is allowed
        val storage = CacheStorage(capacity = 3, threshold = 80)

        // First item: price = 10.0 → sets maxPrice = 10.0
        val result1 = storage.insert(makeResult("dem1", 10.0), sticky = false)
        assertThat(result1.isInserted).isTrue()

        // Second item: price = 7.9 → 7.9 < 10.0 * 0.8 = 8.0 → Rejected
        val result2 = storage.insert(makeResult("dem2", 7.9), sticky = false)
        assertThat(result2).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    @Test
    fun `threshold pass - item at exactly threshold percentage - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        // First item sets max = 10.0
        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // At exactly threshold: 10.0 * 0.8 = 8.0 → pass
        val result = storage.insert(makeResult("dem2", 8.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `threshold pass - item above threshold percentage - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // 9.0 >= 10.0 * 0.8 = 8.0 → pass
        val result = storage.insert(makeResult("dem2", 9.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `threshold - higher price item passes and updates max`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        // higher price → passes (15.0 >= 8.0)
        val result = storage.insert(makeResult("dem2", 15.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `threshold - first item in empty cache always passes`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        // First item in empty cache always accepted, regardless of price
        val result = storage.insert(makeResult("dem1", 0.01), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `threshold - cheap item rejected by threshold on capacity 1`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        // capacity=1, stickyHeadActive=false → step 2 skipped
        // 1.0 < 10.0*0.8=8.0 → Rejected(Threshold)
        val result = storage.insert(makeResult("dem2", 1.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    // -----------------------------------------------------------------------
    // Sticky head protection
    // -----------------------------------------------------------------------

    @Test
    fun `sticky head protection - capacity 1, insert sticky then non-sticky - Rejected StickyProtected`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = true)

        // Any non-sticky insert to a capacity=1 storage with sticky head should be rejected
        val result = storage.insert(makeResult("dem2", 10.0), sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyProtected))
    }

    @Test
    fun `sticky head protection - capacity 1, second sticky insert accepted (caller checks isFull)`() = runTest {
        // Per spec: caller must check !isFull before insert. Inserting into a full
        // capacity-1 cache is undefined. The storage accepts it (no capacity guard).
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = true)

        val dem2 = makeResult("dem2", 10.0)
        val result = storage.insert(dem2, sticky = true)

        // Insert succeeds (step 2 skipped because incoming is sticky)
        assertThat(result.isInserted).isTrue()
    }

    // -----------------------------------------------------------------------
    // Duplicate / double-check (same demandId)
    // -----------------------------------------------------------------------

    @Test
    fun `double check same price - updates in place - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("admob", 5.0), sticky = false)

        val updated = makeResult("admob", 5.0)
        val result = storage.insert(updated, sticky = false)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
    }

    @Test
    fun `double check different price - removes old and re-inserts with new price - Success`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val old = makeResult("admob", 5.0)
        storage.insert(old, sticky = false)

        val updated = makeResult("admob", 9.0)
        val result = storage.insert(updated, sticky = false)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(9.0)
    }

    @Test
    fun `double check same demandId sticky head - duplicate replaces per spec step 4`() = runTest {
        // Spec §3.3: duplicate handling (step 4) runs after sticky check (step 2).
        // Step 2 only triggers for capacity==1. For capacity>1, duplicates replace.
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("admob", 5.0), sticky = true)

        val updated = makeResult("admob", 5.0)
        val result = storage.insert(updated, sticky = false)

        // Duplicate found → old removed (clears sticky) → new inserted → Success
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(updated)
    }

    // -----------------------------------------------------------------------
    // sortAccordingToMode: sticky head stays at [0], tail is sorted
    // -----------------------------------------------------------------------

    @Test
    fun `sortAccordingToMode sticky - sticky head stays at index 0, tail is sorted descending`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)
        // Insert sticky head with medium price
        val stickyItem = makeResult("sticky", 5.0)
        storage.insert(stickyItem, sticky = true)

        // Insert two more items with higher prices — they should go to tail
        storage.insert(makeResult("dem_high", 10.0), sticky = false)
        storage.insert(makeResult("dem_low", 2.0), sticky = false)

        // head must still be the sticky item
        assertThat(storage.state.value.head).isSameInstanceAs(stickyItem)
    }

    @Test
    fun `normal mode sort - items sorted descending by price`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)
        storage.insert(makeResult("dem1", 3.0), sticky = false)
        storage.insert(makeResult("dem2", 7.0), sticky = false)
        storage.insert(makeResult("dem3", 1.0), sticky = false)

        // head should be the highest price
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(7.0)
    }

    // -----------------------------------------------------------------------
    // popFirst
    // -----------------------------------------------------------------------

    @Test
    fun `popFirst - returns head and removes it`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val item1 = makeResult("dem1", 10.0)
        val item2 = makeResult("dem2", 9.0) // 9.0 >= 10.0 * 0.8 = 8.0 → passes threshold
        storage.insert(item1, sticky = false)
        storage.insert(item2, sticky = false)

        val popped = storage.popFirst()

        assertThat(popped).isSameInstanceAs(item1)
        // second item is now the head
        assertThat(storage.state.value.head).isSameInstanceAs(item2)
    }

    @Test
    fun `popFirst - disables sticky mode and resorts remaining items`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val sticky = makeResult("sticky", 5.0)
        storage.insert(sticky, sticky = true)
        storage.insert(makeResult("dem_high", 10.0), sticky = false)
        storage.insert(makeResult("dem_low", 2.0), sticky = false)

        // pop the sticky head
        val popped = storage.popFirst()
        assertThat(popped).isSameInstanceAs(sticky)

        // Now the highest remaining should be head (10.0 > 2.0)
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `popFirst - empty storage returns null`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        val result = storage.popFirst()

        assertThat(result).isNull()
    }

    @Test
    fun `popFirst - single item leaves storage empty`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("dem1", 5.0), sticky = false)

        storage.popFirst()

        assertThat(storage.state.value.head).isNull()
        assertThat(storage.state.value.head).isNull()
    }

    // -----------------------------------------------------------------------
    // peek
    // -----------------------------------------------------------------------

    @Test
    fun `peek - returns head without removing it`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val item = makeResult("dem1", 5.0)
        storage.insert(item, sticky = false)

        val first = storage.state.value.head
        val second = storage.state.value.head

        assertThat(first).isSameInstanceAs(item)
        assertThat(second).isSameInstanceAs(item)
    }

    @Test
    fun `peek - empty storage returns null`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        assertThat(storage.state.value.head).isNull()
        assertThat(storage.state.value.head).isNull()
    }

    // -----------------------------------------------------------------------
    // peekSnapshot (non-suspend)
    // -----------------------------------------------------------------------

    @Test
    fun `peekSnapshot - reflects head after insert`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val item = makeResult("dem1", 5.0)
        storage.insert(item, sticky = false)

        assertThat(storage.state.value.head).isSameInstanceAs(item)
    }

    @Test
    fun `peekSnapshot - null before any insert`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        assertThat(storage.state.value.head).isNull()
    }

    // -----------------------------------------------------------------------
    // Multiple items eviction ordering
    // -----------------------------------------------------------------------

    @Test
    fun `sticky eviction protection - sticky head is never evicted even if cheapest`() = runTest {
        val storage = CacheStorage(capacity = 2, threshold = 80)
        val stickyItem = makeResult("sticky", 1.0) // low price sticky head
        storage.insert(stickyItem, sticky = true)
        storage.insert(makeResult("dem2", 8.0), sticky = false)

        // Try to insert a more expensive item — eviction candidate should be dem2 (not sticky head)
        val newcomer = makeResult("dem3", 10.0)
        val result = storage.insert(newcomer, sticky = false)

        assertThat(result.isInserted).isTrue()
        // sticky head survives
        assertThat(storage.state.value.head).isSameInstanceAs(stickyItem)
    }

    // -----------------------------------------------------------------------
    // Bug fix: capacity==1 sticky insert should replace existing sticky head
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 1 - second sticky insert accepted (caller checks isFull)`() = runTest {
        // Per spec: only one sticky per auction, and caller checks !isFull.
        // If called anyway, insert succeeds (no capacity guard in storage).
        val storage = CacheStorage(capacity = 1, threshold = 80)
        val first = makeResult("admob", 5.0)
        storage.insert(first, sticky = true)

        val second = makeResult("applovin", 8.0)
        val result = storage.insert(second, sticky = true)

        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `capacity 1 - non-sticky insert rejected when sticky head active`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        val sticky = makeResult("admob", 5.0)
        storage.insert(sticky, sticky = true)

        // Non-sticky insert should be rejected
        val nonSticky = makeResult("applovin", 8.0)
        val result = storage.insert(nonSticky, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyProtected))
        assertThat(storage.state.value.head).isSameInstanceAs(sticky)
    }

    @Test
    fun `capacity 1 - non-sticky insert succeeds when no sticky head`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        val first = makeResult("admob", 5.0)
        storage.insert(first, sticky = false)

        // Non-sticky insert should evict existing (no sticky protection)
        val second = makeResult("applovin", 8.0)
        val result = storage.insert(second, sticky = false)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(second)
    }

    // -----------------------------------------------------------------------
    // Threshold edge cases (spec §3.2)
    // -----------------------------------------------------------------------

    @Test
    fun `threshold 0 - all bids pass regardless of price`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 0)
        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // bar = 10.0 * 0 = 0.0; any price >= 0 passes
        val result1 = storage.insert(makeResult("dem2", 1.0), sticky = false)
        val result2 = storage.insert(makeResult("dem3", 0.01), sticky = false)

        assertThat(result1.isInserted).isTrue()
        assertThat(result2.isInserted).isTrue()
        assertThat(storage.state.value.size).isEqualTo(3)
    }

    @Test
    fun `threshold 100 - only bids at or above maxPrice pass`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 100)
        storage.insert(makeResult("dem1", 10.0), sticky = false)

        // bar = 10.0 * 1.0 = 10.0; only >= 10.0 passes
        val reject = storage.insert(makeResult("dem2", 9.99), sticky = false)
        val pass = storage.insert(makeResult("dem3", 10.0), sticky = false)
        val passHigher = storage.insert(makeResult("dem4", 15.0), sticky = false)

        assertThat(reject).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
        assertThat(pass.isInserted).isTrue()
        assertThat(passHigher.isInserted).isTrue()
    }

    // -----------------------------------------------------------------------
    // State tracking via StateFlow
    // -----------------------------------------------------------------------

    @Test
    fun `state isFull - false below capacity, true at capacity`() = runTest {
        val storage = CacheStorage(capacity = 2, threshold = 0)

        assertThat(storage.state.value.isFull).isFalse()

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        assertThat(storage.state.value.isFull).isFalse()

        storage.insert(makeResult("dem2", 5.0), sticky = false)
        assertThat(storage.state.value.isFull).isTrue()
    }

    @Test
    fun `state thresholdBar - null when empty, calculated after insert`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        assertThat(storage.state.value.thresholdBar).isNull()

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        assertThat(storage.state.value.thresholdBar).isEqualTo(8.0) // 10.0 * 0.8
    }

    @Test
    fun `state thresholdBar - recalculated when higher price becomes head`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        storage.insert(makeResult("dem1", 10.0), sticky = false) // bar = 8.0
        storage.insert(makeResult("dem2", 15.0), sticky = false) // head=15.0 → bar = 12.0

        // After sort, 15.0 is head → thresholdBar = 15.0 * 0.8 = 12.0
        assertThat(storage.state.value.thresholdBar).isEqualTo(12.0)
    }

    @Test
    fun `state size - tracks number of items`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 0)

        assertThat(storage.state.value.size).isEqualTo(0)

        storage.insert(makeResult("dem1", 10.0), sticky = false)
        assertThat(storage.state.value.size).isEqualTo(1)

        storage.insert(makeResult("dem2", 8.0), sticky = false)
        assertThat(storage.state.value.size).isEqualTo(2)

        storage.popFirst()
        assertThat(storage.state.value.size).isEqualTo(1)
    }

    @Test
    fun `state hasContent - false when empty, true with items`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)

        assertThat(storage.state.value.hasContent).isFalse()

        storage.insert(makeResult("dem1", 5.0), sticky = false)
        assertThat(storage.state.value.hasContent).isTrue()

        storage.popFirst()
        assertThat(storage.state.value.hasContent).isFalse()
    }

    // -----------------------------------------------------------------------
    // Multiple pops & insert after pop
    // -----------------------------------------------------------------------

    @Test
    fun `multiple pops - return items in descending price order`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 0)
        storage.insert(makeResult("dem1", 3.0), sticky = false)
        storage.insert(makeResult("dem2", 10.0), sticky = false)
        storage.insert(makeResult("dem3", 7.0), sticky = false)

        val first = storage.popFirst()!!
        val second = storage.popFirst()!!
        val third = storage.popFirst()!!

        assertThat(first.adSource.getStats().price).isEqualTo(10.0)
        assertThat(second.adSource.getStats().price).isEqualTo(7.0)
        assertThat(third.adSource.getStats().price).isEqualTo(3.0)
        assertThat(storage.popFirst()).isNull()
    }

    @Test
    fun `insert after pop - capacity freed, new item accepted`() = runTest {
        val storage = CacheStorage(capacity = 2, threshold = 0)
        storage.insert(makeResult("dem1", 10.0), sticky = false)
        storage.insert(makeResult("dem2", 5.0), sticky = false)

        assertThat(storage.state.value.isFull).isTrue()

        storage.popFirst() // removes head, frees one slot
        assertThat(storage.state.value.isFull).isFalse()

        val result = storage.insert(makeResult("dem3", 3.0), sticky = false)
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.isFull).isTrue()
    }

    // -----------------------------------------------------------------------
    // Duplicate vs. different items
    // -----------------------------------------------------------------------

    @Test
    fun `different demandId same price - both coexist, not a duplicate`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("admob", 10.0), sticky = false)

        val result = storage.insert(makeResult("applovin", 10.0), sticky = false)

        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.size).isEqualTo(2)
    }

    @Test
    fun `same demandId different price - NOT deduplicated (spec checks both key and price)`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 0)
        storage.insert(makeResult("admob", 10.0), sticky = false)

        val result = storage.insert(makeResult("admob", 8.0), sticky = false)

        // Different price → not a duplicate → both kept
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.size).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // popFirst clears sticky → subsequent non-sticky inserts work
    // -----------------------------------------------------------------------

    @Test
    fun `popFirst clears sticky - capacity 1, then non-sticky insert accepted`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("dem1", 10.0), sticky = true)

        // Before pop, non-sticky is rejected
        val reject = storage.insert(makeResult("dem2", 15.0), sticky = false)
        assertThat(reject).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyProtected))

        // Pop clears sticky
        storage.popFirst()

        // Now non-sticky is accepted (empty cache)
        val accept = storage.insert(makeResult("dem3", 8.0), sticky = false)
        assertThat(accept.isInserted).isTrue()
    }

    @Test
    fun `isFull not reached - caller can keep inserting until capacity`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 0)

        for (i in 1..3) {
            assertThat(storage.state.value.isFull).isFalse()
            storage.insert(makeResult("dem$i", (10 - i).toDouble()), sticky = i == 1)
        }
        assertThat(storage.state.value.isFull).isTrue()
    }

    // -----------------------------------------------------------------------
    // Threshold recalculation after higher price becomes head
    // -----------------------------------------------------------------------

    @Test
    fun `threshold bar updates when higher price item becomes new head`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)

        storage.insert(makeResult("dem1", 10.0), sticky = false) // bar = 8.0
        assertThat(storage.state.value.thresholdBar).isEqualTo(8.0)

        storage.insert(makeResult("dem2", 20.0), sticky = false) // new head → bar = 16.0
        assertThat(storage.state.value.thresholdBar).isEqualTo(16.0)

        // 12.0 < 16.0 → rejected now (would have passed with old bar 8.0)
        val result = storage.insert(makeResult("dem3", 12.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    @Test
    fun `threshold bar recalculated after pop - lower head gives lower bar`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)

        storage.insert(makeResult("dem1", 20.0), sticky = false) // head=20, bar=16
        storage.insert(makeResult("dem2", 18.0), sticky = false) // 18 >= 16 → passes
        assertThat(storage.state.value.thresholdBar).isEqualTo(16.0)

        storage.popFirst() // removes 20.0; new head=18.0, bar=14.4
        assertThat(storage.state.value.thresholdBar).isEqualTo(14.4)

        // 15.0 >= 14.4 → passes (would fail with old bar 16.0)
        val result = storage.insert(makeResult("dem3", 15.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    // -----------------------------------------------------------------------
    // Capacity 10 (max allowed by config)
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 10 - fills completely with valid threshold bids`() = runTest {
        val storage = CacheStorage(capacity = 10, threshold = 0)

        for (i in 1..10) {
            val result = storage.insert(makeResult("dem$i", (100 - i).toDouble()), sticky = i == 1)
            assertThat(result.isInserted).isTrue()
        }
        assertThat(storage.state.value.isFull).isTrue()
        assertThat(storage.state.value.size).isEqualTo(10)
    }

    @Test
    fun `capacity 10 - pop all items returns in descending price order`() = runTest {
        val storage = CacheStorage(capacity = 10, threshold = 0)
        val prices = listOf(5.0, 3.0, 8.0, 1.0, 9.0, 2.0, 7.0, 4.0, 10.0, 6.0)
        for ((i, price) in prices.withIndex()) {
            storage.insert(makeResult("dem$i", price), sticky = false)
        }

        val popped = mutableListOf<Double>()
        while (true) {
            val item = storage.popFirst() ?: break
            popped.add(item.adSource.getStats().price)
        }

        assertThat(popped).isEqualTo(prices.sortedDescending())
    }

    // -----------------------------------------------------------------------
    // Price edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `price zero - first item with zero price accepted`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val result = storage.insert(makeResult("dem1", 0.0), sticky = false)
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.thresholdBar).isEqualTo(0.0) // 0.0 * 0.8 = 0.0
    }

    @Test
    fun `price zero head - second zero price item passes threshold (0 ge 0)`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("dem1", 0.0), sticky = false)

        // bar = 0.0 * 0.8 = 0.0; 0.0 >= 0.0 → passes
        val result = storage.insert(makeResult("dem2", 0.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    @Test
    fun `very small prices - fractional comparison works correctly`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("dem1", 0.001), sticky = false)
        // bar = 0.001 * 0.8 = 0.0008

        val pass = storage.insert(makeResult("dem2", 0.001), sticky = false) // 0.001 >= 0.0008
        val reject = storage.insert(makeResult("dem3", 0.0001), sticky = false) // 0.0001 < 0.0008

        assertThat(pass.isInserted).isTrue()
        assertThat(reject).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    @Test
    fun `large prices - no overflow issues`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("dem1", 999999.99), sticky = false)
        // bar = 999999.99 * 0.8 = 799999.992

        val result = storage.insert(makeResult("dem2", 800000.0), sticky = false)
        assertThat(result.isInserted).isTrue()
    }

    // -----------------------------------------------------------------------
    // Sticky + threshold interaction
    // -----------------------------------------------------------------------

    @Test
    fun `sticky head low price - threshold bar uses sticky head price`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("sticky", 2.0), sticky = true) // bar = 2.0 * 0.8 = 1.6

        // 1.5 < 1.6 → rejected
        val reject = storage.insert(makeResult("dem2", 1.5), sticky = false)
        assertThat(reject).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))

        // 1.6 >= 1.6 → passes
        val pass = storage.insert(makeResult("dem3", 1.6), sticky = false)
        assertThat(pass.isInserted).isTrue()
    }

    @Test
    fun `sticky head - non-sticky higher price does NOT become head`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        val sticky = makeResult("sticky", 5.0)
        storage.insert(sticky, sticky = true)
        storage.insert(makeResult("higher", 20.0), sticky = false)

        // Sticky head stays at position 0 despite lower price
        assertThat(storage.state.value.head).isSameInstanceAs(sticky)
        // BUT thresholdBar is based on items[0] which is sticky head
        assertThat(storage.state.value.thresholdBar).isEqualTo(4.0) // 5.0 * 0.8
    }

    @Test
    fun `after pop sticky - new non-sticky head updates threshold bar`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 80)
        storage.insert(makeResult("sticky", 5.0), sticky = true)
        storage.insert(makeResult("higher", 20.0), sticky = false)

        // Before pop: bar = 5.0 * 0.8 = 4.0 (based on sticky head)
        assertThat(storage.state.value.thresholdBar).isEqualTo(4.0)

        storage.popFirst() // removes sticky; head becomes 20.0
        // After pop: bar = 20.0 * 0.8 = 16.0
        assertThat(storage.state.value.thresholdBar).isEqualTo(16.0)
    }

    // -----------------------------------------------------------------------
    // Duplicate edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `duplicate sticky head at capacity 1 - non-sticky rejected by StickyProtected before duplicate check`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("admob", 10.0), sticky = true)

        // Same demandId + same price, but step 2 (StickyProtected) fires before step 4 (duplicate)
        val updated = makeResult("admob", 10.0)
        val result = storage.insert(updated, sticky = false)

        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.StickyProtected))
    }

    @Test
    fun `multiple different demands fill cache exactly`() = runTest {
        val storage = CacheStorage(capacity = 3, threshold = 0)
        val results = listOf(
            makeResult("admob", 10.0),
            makeResult("applovin", 8.0),
            makeResult("unity", 6.0),
        )
        for (r in results) {
            assertThat(storage.insert(r, sticky = false).isInserted).isTrue()
        }
        assertThat(storage.state.value.isFull).isTrue()
        assertThat(storage.state.value.size).isEqualTo(3)
    }

    // -----------------------------------------------------------------------
    // Concurrent operations
    // -----------------------------------------------------------------------

    @Test
    fun `concurrent inserts - all complete without exception`() = runTest {
        val storage = CacheStorage(capacity = 50, threshold = 0)

        val results = (1..50).map { i ->
            async {
                storage.insert(makeResult("dem$i", i.toDouble()), sticky = false)
            }
        }.awaitAll()

        // All should succeed (capacity allows all)
        assertThat(results.all { it.isInserted }).isTrue()
        assertThat(storage.state.value.size).isEqualTo(50)
        assertThat(storage.state.value.isFull).isTrue()
    }

    @Test
    fun `concurrent insert and pop - no crash, consistent state`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 0)

        // Pre-fill
        for (i in 1..5) {
            storage.insert(makeResult("dem$i", i.toDouble()), sticky = false)
        }

        // Concurrent pops
        val popped = (1..5).map {
            async { storage.popFirst() }
        }.awaitAll()

        // Exactly 5 non-null results, no duplicates
        val nonNull = popped.filterNotNull()
        assertThat(nonNull).hasSize(5)
        assertThat(storage.state.value.size).isEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // Sort stability — same price items
    // -----------------------------------------------------------------------

    @Test
    fun `same price different demands - all coexist in cache`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 0)
        storage.insert(makeResult("admob", 5.0), sticky = false)
        storage.insert(makeResult("applovin", 5.0), sticky = false)
        storage.insert(makeResult("unity", 5.0), sticky = false)

        assertThat(storage.state.value.size).isEqualTo(3)
    }

    // -----------------------------------------------------------------------
    // Threshold with ascending prices (head changes)
    // -----------------------------------------------------------------------

    @Test
    fun `ascending prices - each new insert becomes head, bar rises`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 80)

        storage.insert(makeResult("dem1", 5.0), sticky = false) // head=5, bar=4
        assertThat(storage.state.value.thresholdBar).isEqualTo(4.0)

        storage.insert(makeResult("dem2", 10.0), sticky = false) // head=10, bar=8
        assertThat(storage.state.value.thresholdBar).isEqualTo(8.0)

        storage.insert(makeResult("dem3", 20.0), sticky = false) // head=20, bar=16
        assertThat(storage.state.value.thresholdBar).isEqualTo(16.0)

        // 10.0 < 16.0 → rejected
        val result = storage.insert(makeResult("dem4", 10.0), sticky = false)
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    // -----------------------------------------------------------------------
    // Insert after full drain and refill
    // -----------------------------------------------------------------------

    @Test
    fun `full drain and refill - cache resets properly`() = runTest {
        val storage = CacheStorage(capacity = 2, threshold = 0)

        // Fill
        storage.insert(makeResult("dem1", 10.0), sticky = false)
        storage.insert(makeResult("dem2", 5.0), sticky = false)
        assertThat(storage.state.value.isFull).isTrue()

        // Drain
        storage.popFirst()
        storage.popFirst()
        assertThat(storage.state.value.size).isEqualTo(0)
        assertThat(storage.state.value.hasContent).isFalse()
        assertThat(storage.state.value.thresholdBar).isNull()

        // Refill — first item always passes (empty cache rule)
        val result = storage.insert(makeResult("dem3", 1.0), sticky = true)
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(1.0)
    }

    // -----------------------------------------------------------------------
    // Capacity 1 extensive testing
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 1 non-sticky - higher price replaces lower`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 0)
        storage.insert(makeResult("dem1", 5.0), sticky = false)

        val result = storage.insert(makeResult("dem2", 10.0), sticky = false)
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(10.0)
    }

    @Test
    fun `capacity 1 non-sticky - lower price rejected by threshold`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("dem1", 10.0), sticky = false) // bar = 8.0

        val result = storage.insert(makeResult("dem2", 5.0), sticky = false) // 5 < 8 → reject
        assertThat(result).isEqualTo(InsertResult.Rejected(InsertResult.Reason.Threshold))
    }

    @Test
    fun `capacity 1 - pop then reuse with new sticky`() = runTest {
        val storage = CacheStorage(capacity = 1, threshold = 80)
        storage.insert(makeResult("dem1", 10.0), sticky = true)

        storage.popFirst() // clears sticky + cache

        // New sticky insert as if new auction
        val newSticky = makeResult("dem2", 8.0)
        val result = storage.insert(newSticky, sticky = true)
        assertThat(result.isInserted).isTrue()
        assertThat(storage.state.value.head).isSameInstanceAs(newSticky)
    }

    // -----------------------------------------------------------------------
    // CacheSnapshot data class
    // -----------------------------------------------------------------------

    @Test
    fun `CacheSnapshot Empty - all fields default`() {
        val empty = CacheSnapshot.Empty
        assertThat(empty.head).isNull()
        assertThat(empty.isFull).isFalse()
        assertThat(empty.thresholdBar).isNull()
        assertThat(empty.size).isEqualTo(0)
        assertThat(empty.hasContent).isFalse()
    }

    @Test
    fun `CacheSnapshot hasContent - derived from head`() {
        val withHead = CacheSnapshot(head = mockk(relaxed = true), isFull = false, thresholdBar = null, size = 1)
        assertThat(withHead.hasContent).isTrue()

        val withoutHead = CacheSnapshot(head = null, isFull = false, thresholdBar = null, size = 0)
        assertThat(withoutHead.hasContent).isFalse()
    }

    // -----------------------------------------------------------------------
    // Sticky mode: multi-item cache (capacity > 1)
    // -----------------------------------------------------------------------

    @Test
    fun `capacity 2 - sticky + non-sticky fill, pop sticky clears mode`() = runTest {
        val storage = CacheStorage(capacity = 2, threshold = 0)
        val sticky = makeResult("dem1", 3.0)
        val nonSticky = makeResult("dem2", 10.0)

        storage.insert(sticky, sticky = true)
        storage.insert(nonSticky, sticky = false)

        // Head is sticky despite lower price
        assertThat(storage.state.value.head).isSameInstanceAs(sticky)

        // Pop sticky
        val popped = storage.popFirst()
        assertThat(popped).isSameInstanceAs(sticky)

        // After pop: resort, non-sticky is now head
        assertThat(storage.state.value.head).isSameInstanceAs(nonSticky)
    }

    @Test
    fun `capacity 3 - sticky low, two higher - sort keeps sticky at 0`() = runTest {
        val storage = CacheStorage(capacity = 5, threshold = 0)
        storage.insert(makeResult("sticky", 1.0), sticky = true)
        storage.insert(makeResult("high1", 50.0), sticky = false)
        storage.insert(makeResult("high2", 30.0), sticky = false)

        // Sticky at head despite being cheapest
        assertThat(storage.state.value.head!!.adSource.getStats().price).isEqualTo(1.0)
        assertThat(storage.state.value.head!!.adSource.getStats().demandId.demandId).isEqualTo("sticky")
    }
}
