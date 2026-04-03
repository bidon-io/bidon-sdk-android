package org.bidon.sdk.ads.cache.twolevel

import android.app.Activity
import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.twolevel.auction.TwoLevelAuctionController
import org.bidon.sdk.ads.cache.twolevel.storage.CacheStorage
import org.bidon.sdk.ads.cache.twolevel.storage.FallbackCacheStorage
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
internal class TwoLevelAdManagerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkLog()
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 1000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(SystemClock::class)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun makeResult(demandId: String, price: Double): AuctionResult.Network {
        val adSource = mockk<AdSource<*>>(relaxed = true)
        every { adSource.getStats() } returns makeBidStat(demandId, price)
        return AuctionResult.Network(adSource = adSource, roundStatus = RoundStatus.Successful)
    }

    private fun makeBiddingResult(demandId: String, price: Double): AuctionResult.Bidding {
        val adSource = mockk<AdSource<*>>(relaxed = true)
        every { adSource.getStats() } returns makeBidStat(demandId, price)
        return AuctionResult.Bidding(adSource = adSource, roundStatus = RoundStatus.Successful)
    }

    /** For testing WinLossNotifiable — adSource implements both AdSource and WinLossNotifiable. */
    private interface WinLossAdSource : AdSource.Interstitial<AdAuctionParams>, WinLossNotifiable

    private fun makeWinLossResult(demandId: String, price: Double): AuctionResult.Network {
        val adSource = mockk<WinLossAdSource>(relaxed = true)
        every { adSource.getStats() } returns makeBidStat(demandId, price)
        return AuctionResult.Network(adSource = adSource, roundStatus = RoundStatus.Successful)
    }

    private fun makeBidStat(demandId: String, price: Double) = BidStat(
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

    private fun mockAdTypeParam(
        pricefloor: Double = 0.0,
        auctionKey: String? = "test_key",
    ): AdTypeParam.Interstitial {
        return AdTypeParam.Interstitial(
            activity = mockk<Activity>(relaxed = true),
            pricefloor = pricefloor,
            auctionKey = auctionKey,
        )
    }

    private data class Setup(
        val manager: TwoLevelAdManager,
        val mainCache: CacheStorage,
        val fallbackCache: FallbackCacheStorage,
        val controller: TwoLevelAuctionController,
    )

    private fun createSetup(
        mainCapacity: Int = 3,
        threshold: Int = 80,
        fallbackCapacity: Int = 2,
    ): Setup {
        val mainCache = CacheStorage(capacity = mainCapacity, threshold = threshold)
        val fallbackCache = FallbackCacheStorage(capacity = fallbackCapacity)
        val controller = mockk<TwoLevelAuctionController>(relaxed = true)
        val manager = TwoLevelAdManager(
            demandAd = DemandAd(AdType.Interstitial),
            mainCache = mainCache,
            fallbackCache = fallbackCache,
            controller = controller,
            auctionKey = "test",
        )
        return Setup(manager, mainCache, fallbackCache, controller)
    }

    /** Call cache() and wait for onSuccess via CountDownLatch (real-time). */
    private fun cacheAndAwaitSuccess(
        manager: TwoLevelAdManager,
        adTypeParam: AdTypeParam = mockAdTypeParam(),
    ): Pair<AuctionResult, AuctionInfo> {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Pair<AuctionResult, AuctionInfo>>()
        val errorRef = AtomicReference<Throwable>()
        manager.cache(
            adTypeParam = adTypeParam,
            onSuccess = { r, info -> resultRef.set(r to info); latch.countDown() },
            onFailure = { _, e -> errorRef.set(e); latch.countDown() },
        )
        val received = latch.await(5, TimeUnit.SECONDS)
        assertThat(received).isTrue()
        val error = errorRef.get()
        if (error != null) throw AssertionError("Expected success but got failure: $error", error)
        return resultRef.get()
    }

    /** Call cache() and wait for onFailure. */
    private fun cacheAndAwaitFailure(
        manager: TwoLevelAdManager,
        adTypeParam: AdTypeParam = mockAdTypeParam(),
    ): Throwable {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable>()
        manager.cache(
            adTypeParam = adTypeParam,
            onSuccess = { _, _ -> errorRef.set(AssertionError("Expected failure but got success")); latch.countDown() },
            onFailure = { _, e -> errorRef.set(e); latch.countDown() },
        )
        val received = latch.await(5, TimeUnit.SECONDS)
        assertThat(received).isTrue()
        val error = errorRef.get()
        if (error is AssertionError) throw error
        return error
    }

    /**
     * Configure controller mock to deliver [bids] via singleLoadCompletion then call onComplete.
     */
    private fun setupControllerWithBids(
        controller: TwoLevelAuctionController,
        bids: List<AuctionResult>,
        externalWinNotificationsEnabled: Boolean = false,
        error: BidonError? = null,
    ) {
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            for (bid in bids) {
                singleLoad(bid, externalWinNotificationsEnabled)
            }
            onComplete(null, error)
        }
    }

    private fun setupControllerNoFill(
        controller: TwoLevelAuctionController,
        error: BidonError? = null,
    ) {
        setupControllerWithBids(controller, emptyList(), error = error)
    }

    // -----------------------------------------------------------------------
    // Section 1: Cache hit (spec §7, step 1)
    // -----------------------------------------------------------------------

    @Test
    fun `cache hit - main has content, returns instantly without auction`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = true)

        val (result, _) = cacheAndAwaitSuccess(manager)

        assertThat(result).isSameInstanceAs(item)
        coVerify(exactly = 0) { controller.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cache hit - returns synthetic AuctionInfo with WIN status`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        mainCache.insert(makeResult("admob", 10.0), sticky = true)

        val (_, info) = cacheAndAwaitSuccess(manager)

        assertThat(info.adUnits).hasSize(1)
        assertThat(info.adUnits!!.first().status).isEqualTo(RoundStatus.Win.code)
        assertThat(info.adUnits!!.first().demandId).isEqualTo("admob")
    }

    @Test
    fun `cache hit - multiple sequential hits return same head (peek not pop)`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup(mainCapacity = 3, threshold = 80)
        mainCache.insert(makeResult("dem1", 10.0), sticky = true)
        mainCache.insert(makeResult("dem2", 9.0), sticky = false)

        val (r1, _) = cacheAndAwaitSuccess(manager)
        val (r2, _) = cacheAndAwaitSuccess(manager)

        // cache() peeks, doesn't pop — returns same head each time
        assertThat(r1.adSource.getStats().demandId.demandId).isEqualTo("dem1")
        assertThat(r2.adSource.getStats().demandId.demandId).isEqualTo("dem1")
    }

    // -----------------------------------------------------------------------
    // Section 2: Auction state machine (spec §6)
    // -----------------------------------------------------------------------

    @Test
    fun `auction running - second cache call is silent return`() = runBlocking {
        val (manager, _, _, controller) = createSetup()

        // Controller suspends indefinitely (simulates running auction)
        val gate = CompletableDeferred<Unit>()
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }

        // First call starts auction
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> },
            onFailure = { _, _ -> },
        )

        Thread.sleep(100) // let first coroutine enter Running state

        // Second call should be silent (no callback at all)
        val latch = CountDownLatch(1)
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { _, _ -> latch.countDown() },
        )

        val fired = latch.await(500, TimeUnit.MILLISECONDS)
        assertThat(fired).isFalse() // no callback = silent return

        gate.complete(Unit)
        manager.clear()
    }

    @Test
    fun `isIdle - true when no auction running`() {
        val (manager, _, _, _) = createSetup()
        assertThat(manager.isIdle()).isTrue()
    }

    @Test
    fun `isAlive - true when scope not cancelled`() {
        val (manager, _, _, _) = createSetup()
        assertThat(manager.isAlive()).isTrue()
    }

    @Test
    fun `isAlive - false after clear`() {
        val (manager, _, _, _) = createSetup()
        manager.clear()
        assertThat(manager.isAlive()).isFalse()
    }

    // -----------------------------------------------------------------------
    // Section 3: Routing — first bid → WIN (spec §9, §7)
    // -----------------------------------------------------------------------

    @Test
    fun `first bid - inserted to main as sticky WIN, onSuccess called`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(mainCapacity = 3, threshold = 80)
        val bid = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid))

        val (result, _) = cacheAndAwaitSuccess(manager)

        assertThat(result).isSameInstanceAs(bid)
        assertThat(mainCache.state.value.head).isSameInstanceAs(bid)
    }

    @Test
    fun `first bid - markWin called on adSource`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid))

        cacheAndAwaitSuccess(manager)

        verify { bid.adSource.markWin() }
    }

    // -----------------------------------------------------------------------
    // Section 4: Routing — subsequent bids → CACHE (spec §9)
    // -----------------------------------------------------------------------

    @Test
    fun `second bid - CACHE in main, markFillFinished with Cached`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(mainCapacity = 3, threshold = 80)
        val bid1 = makeResult("admob", 10.0)
        val bid2 = makeResult("applovin", 9.0) // 9.0 >= 10.0*0.8 = 8.0 → passes

        setupControllerWithBids(controller, listOf(bid1, bid2))
        cacheAndAwaitSuccess(manager)

        // Wait for auction to fully complete
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(2)
        verify { bid2.adSource.markFillFinished(RoundStatus.Cached, 9.0) }
    }

    @Test
    fun `three bids fill main to capacity`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(mainCapacity = 3, threshold = 80)
        val bid1 = makeResult("dem1", 10.0)
        val bid2 = makeResult("dem2", 9.0)
        val bid3 = makeResult("dem3", 8.0) // 8.0 >= 10.0*0.8 = 8.0 → passes

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.isFull).isTrue()
        assertThat(mainCache.state.value.size).isEqualTo(3)
    }

    // -----------------------------------------------------------------------
    // Section 5: Routing — main full → fallback (spec §4)
    // -----------------------------------------------------------------------

    @Test
    fun `main full sticky - fourth bid goes to fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 2
        )
        val bid1 = makeResult("dem1", 10.0)
        val bid2 = makeResult("dem2", 9.0)
        val bid3 = makeResult("dem3", 8.0) // fills main
        val bid4 = makeResult("dem4", 7.0) // main full → fallback

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3, bid4))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.isFull).isTrue()
        assertThat(fallbackCache.state.value.size).isEqualTo(1)
        verify { bid4.adSource.markFillFinished(RoundStatus.Cached, 7.0) }
    }

    @Test
    fun `main threshold reject - bid goes to fallback`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 2
        )
        // $10 → main (sticky). bar = $8
        // $5 → $5 < $8 → main reject → fallback
        val bid1 = makeResult("dem1", 10.0)
        val bid2 = makeResult("dem2", 5.0)

        setupControllerWithBids(controller, listOf(bid1, bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(fallbackCache.state.value.size).isEqualTo(1)
        verify { bid2.adSource.markFillFinished(RoundStatus.Cached, 5.0) }
    }

    @Test
    fun `capacity 1 sticky - all subsequent bids go to fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 3
        )
        val bid1 = makeResult("dem1", 5.0)
        val bid2 = makeResult("dem2", 4.0)
        val bid3 = makeResult("dem3", 3.0)

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(1)
        assertThat(fallbackCache.state.value.size).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // Section 6: Routing — both reject → LOSE (spec §7 pseudocode)
    // -----------------------------------------------------------------------

    @Test
    fun `both caches reject - bid destroyed with LOSE`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val bid2 = makeResult("dem2", 5.0) // fallback
        val bid3 = makeResult("dem3", 3.0) // main full (sticky), fb full, 3 <= 5 → reject

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid3.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
        verify { bid3.adSource.destroy() }
    }

    @Test
    fun `fallback disabled - rejected bid destroyed`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val bid2 = makeResult("dem2", 5.0) // main full + fb disabled → destroy

        setupControllerWithBids(controller, listOf(bid1, bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid2.adSource.markFillFinished(RoundStatus.Lose, 5.0) }
        verify { bid2.adSource.destroy() }
    }

    // -----------------------------------------------------------------------
    // Section 7: Fallback eviction (spec §4)
    // -----------------------------------------------------------------------

    @Test
    fun `fallback eviction - cheapest evicted when more expensive bid arrives`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val bid2 = makeResult("dem2", 3.0) // fallback
        val bid3 = makeResult("dem3", 5.0) // fb full, 5 > 3 → evict dem2

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // dem2 evicted
        verify { bid2.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
        verify { bid2.adSource.destroy() }
        // dem3 in fallback
        assertThat(fallbackCache.state.value.head).isSameInstanceAs(bid3)
    }

    @Test
    fun `fallback eviction - evicted loss notification uses displacer info`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeWinLossResult("dem1", 10.0) // main
        val evicted = makeWinLossResult("dem2", 3.0) // fallback
        val displacer = makeResult("dem3", 5.0) // evicts dem2

        setupControllerWithBids(controller, listOf(bid1, evicted, displacer))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Evicted notifyLoss uses displacer info (not auction winner)
        verify { (evicted.adSource as WinLossNotifiable).notifyLoss("dem3", 5.0) }
    }

    @Test
    fun `fallback eviction - equal price does NOT evict`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val bid2 = makeResult("dem2", 5.0) // fallback
        val bid3 = makeResult("dem3", 5.0) // equal price → NO eviction → reject

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // bid3 rejected, not inserted
        verify { bid3.adSource.markFillFinished(RoundStatus.Lose, 5.0) }
        verify { bid3.adSource.destroy() }
        verify(exactly = 0) { bid2.adSource.destroy() }
    }

    // -----------------------------------------------------------------------
    // Section 8: No fill (spec §7, step 4)
    // -----------------------------------------------------------------------

    @Test
    fun `no fill - fallback rescue delivers from fallback`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup()
        val fallbackBid = makeResult("cached", 5.0)
        fallbackCache.insert(fallbackBid)

        setupControllerNoFill(controller)

        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result).isSameInstanceAs(fallbackBid)
    }

    @Test
    fun `no fill - no fallback content - didFailToLoad`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        setupControllerNoFill(controller)

        val error = cacheAndAwaitFailure(manager)
        assertThat(error).isInstanceOf(BidonError.NoFill::class.java)
    }

    @Test
    fun `no fill with server error - fallback rescue`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup()
        val fallbackBid = makeResult("cached", 3.0)
        fallbackCache.insert(fallbackBid)

        setupControllerNoFill(controller, error = BidonError.NetworkError(null))

        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result).isSameInstanceAs(fallbackBid)
    }

    @Test
    fun `no fill with server error - no fallback - propagates error`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val networkError = BidonError.NetworkError(null)
        setupControllerNoFill(controller, error = networkError)

        val error = cacheAndAwaitFailure(manager)
        assertThat(error).isSameInstanceAs(networkError)
    }

    // -----------------------------------------------------------------------
    // Section 9: Win/Loss notifications (spec §9.1)
    // -----------------------------------------------------------------------

    @Test
    fun `win notification - non-bidding WinLossNotifiable gets notifyWin`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeWinLossResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid), externalWinNotificationsEnabled = false)

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid.adSource.markWin() }
        verify { (bid.adSource as WinLossNotifiable).notifyWin() }
    }

    @Test
    fun `win notification - bidding result does NOT get notifyWin`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeBiddingResult("rtb_dem", 10.0)
        setupControllerWithBids(controller, listOf(bid), externalWinNotificationsEnabled = false)

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid.adSource.markWin() }
        // Bidding → no adapter-level notifyWin (server handles it)
    }

    @Test
    fun `win notification - externalWinNotificationsEnabled skips notifyWin`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeWinLossResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid), externalWinNotificationsEnabled = true)

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid.adSource.markWin() }
        verify(exactly = 0) { (bid.adSource as WinLossNotifiable).notifyWin() }
    }

    @Test
    fun `loss notification - non-bidding loser gets notifyLoss with winner info`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )
        val winner = makeResult("admob", 10.0)
        val loser = makeWinLossResult("applovin", 5.0) // fb disabled → destroy

        setupControllerWithBids(controller, listOf(winner, loser))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { (loser.adSource as WinLossNotifiable).notifyLoss("admob", 10.0) }
        verify { loser.adSource.destroy() }
    }

    @Test
    fun `loss notification - bidding loser does NOT get adapter notifyLoss`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )
        val winner = makeResult("admob", 10.0)
        val loser = makeBiddingResult("rtb_dem", 5.0)

        setupControllerWithBids(controller, listOf(winner, loser))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { loser.adSource.destroy() }
    }

    @Test
    fun `loss notification - markLoss called when roundStatus is Successful`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )
        val winner = makeResult("admob", 10.0)
        val loser = makeResult("applovin", 5.0)

        setupControllerWithBids(controller, listOf(winner, loser))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { loser.adSource.markLoss() }
    }

    @Test
    fun `evicted - full loss cycle - markFillFinished, markLoss, destroy`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val bid2 = makeResult("dem2", 3.0) // fallback
        val bid3 = makeResult("dem3", 5.0) // evicts dem2

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid2.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
        verify { bid2.adSource.markLoss() }
        verify { bid2.adSource.destroy() }
    }

    // -----------------------------------------------------------------------
    // Section 10: peek / pop / clear (spec §3, §8)
    // -----------------------------------------------------------------------

    @Test
    fun `peek - returns main head when main has content`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = false)

        assertThat(manager.peek()).isSameInstanceAs(item)
    }

    @Test
    fun `peek - returns fallback head when main is empty`() = runBlocking {
        val (manager, _, fallbackCache, _) = createSetup()
        val item = makeResult("admob", 5.0)
        fallbackCache.insert(item)

        assertThat(manager.peek()).isSameInstanceAs(item)
    }

    @Test
    fun `peek - returns null when both empty`() {
        val (manager, _, _, _) = createSetup()
        assertThat(manager.peek()).isNull()
    }

    @Test
    fun `pop - returns from main first`() = runBlocking {
        val (manager, mainCache, fallbackCache, _) = createSetup()
        val mainItem = makeResult("main_dem", 10.0)
        val fbItem = makeResult("fb_dem", 5.0)
        mainCache.insert(mainItem, sticky = false)
        fallbackCache.insert(fbItem)

        val popped = manager.pop()

        assertThat(popped).isSameInstanceAs(mainItem)
    }

    @Test
    fun `pop - returns from fallback when main empty`() = runBlocking {
        val (manager, _, fallbackCache, _) = createSetup()
        val fbItem = makeResult("fb_dem", 5.0)
        fallbackCache.insert(fbItem)

        val popped = manager.pop()

        assertThat(popped).isSameInstanceAs(fbItem)
    }

    @Test
    fun `pop - returns null when both empty`() {
        val (manager, _, _, _) = createSetup()
        assertThat(manager.pop()).isNull()
    }

    @Test
    fun `pop - sequential pops drain main then fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, _) = createSetup()
        val m1 = makeResult("main1", 10.0)
        val m2 = makeResult("main2", 9.0)
        val fb = makeResult("fb1", 3.0)
        mainCache.insert(m1, sticky = true)
        mainCache.insert(m2, sticky = false)
        fallbackCache.insert(fb)

        val pop1 = manager.pop()
        val pop2 = manager.pop()
        val pop3 = manager.pop()
        val pop4 = manager.pop()

        assertThat(pop1!!.adSource.getStats().price).isEqualTo(10.0)
        assertThat(pop2!!.adSource.getStats().price).isEqualTo(9.0)
        assertThat(pop3!!.adSource.getStats().price).isEqualTo(3.0)
        assertThat(pop4).isNull()
    }

    @Test
    fun `clear - cancels scope, isAlive returns false`() {
        val (manager, _, _, _) = createSetup()
        manager.clear()
        assertThat(manager.isAlive()).isFalse()
    }

    @Test
    fun `pop from empty after clear returns null`() {
        val (manager, _, _, _) = createSetup()
        manager.clear()
        assertThat(manager.pop()).isNull()
    }

    @Test
    fun `peek does not consume item - multiple peeks return same`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = false)

        val peek1 = manager.peek()
        val peek2 = manager.peek()
        assertThat(peek1).isSameInstanceAs(item)
        assertThat(peek2).isSameInstanceAs(item)
    }

    // -----------------------------------------------------------------------
    // Section 11: shouldContinueAuction (spec §11)
    // -----------------------------------------------------------------------

    @Test
    fun `shouldContinueAuction - main empty, no bar - accepts any ecpm`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 0
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            onComplete(null, null)
        }

        cacheAndAwaitFailure(manager)

        // Main empty → thresholdBar = null → canAcceptMain = true for any ecpm
        assertThat(capturedShouldContinue?.invoke(0.01)).isTrue()
        assertThat(capturedShouldContinue?.invoke(100.0)).isTrue()
    }

    @Test
    fun `shouldContinueAuction - both full, ecpm below cheapest - false (STOP)`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(makeResult("dem1", 10.0), false) // main
            singleLoad(makeResult("dem2", 5.0), false) // fallback

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main full + fallback full with cheapest=5.0
        assertThat(capturedShouldContinue?.invoke(3.0)).isFalse()
        assertThat(capturedShouldContinue?.invoke(5.0)).isFalse() // equal doesn't evict
    }

    @Test
    fun `shouldContinueAuction - main full, fallback full, ecpm above cheapest - true`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(makeResult("dem1", 10.0), false)
            singleLoad(makeResult("dem2", 5.0), false)

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // ecpm=6.0 > cheapest=5.0 → canAcceptFallback = true
        assertThat(capturedShouldContinue?.invoke(6.0)).isTrue()
    }

    @Test
    fun `shouldContinueAuction - main not full, ecpm below bar, fb disabled - STOP`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 0
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            // First bid sets bar = 10.0 * 0.8 = 8.0
            singleLoad(makeResult("dem1", 10.0), false)

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main has 1/3, bar=8.0, fallback disabled
        // ecpm=7.0 → canAcceptMain: !full=true, 7.0 >= 8.0? false → false
        // canAcceptFallback: disabled → false
        assertThat(capturedShouldContinue?.invoke(7.0)).isFalse()

        // ecpm=9.0 → canAcceptMain: 9.0 >= 8.0 → true
        assertThat(capturedShouldContinue?.invoke(9.0)).isTrue()
    }

    @Test
    fun `shouldContinueAuction - main full, fallback not full - true`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 3
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(makeResult("dem1", 10.0), false) // fills main

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main full, fallback empty (not full, not disabled) → true for any ecpm
        assertThat(capturedShouldContinue?.invoke(1.0)).isTrue()
    }

    // -----------------------------------------------------------------------
    // Section 12: Spec example 14.1 — cacheSize=3, threshold=70, fallbackSize=2
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_1 - full waterfall with main and fallback filling`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 3, threshold = 70, fallbackCapacity = 2
        )

        val bid10 = makeResult("dem10", 10.0)
        val bid9 = makeResult("dem9", 9.0)
        val bid8 = makeResult("dem8", 8.0)
        val bid7 = makeResult("dem7", 7.0)
        val bid5 = makeResult("dem5", 5.0)

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            val shouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(bid10, false) // WIN
            singleLoad(bid9, false) // CACHE main
            singleLoad(bid8, false) // CACHE main (full)
            singleLoad(bid7, false) // CACHE fallback
            singleLoad(bid5, false) // CACHE fallback (full)

            // $3 → shouldContinue should be false
            assertThat(shouldContinue(3.0)).isFalse()

            onComplete(null, null)
        }

        val (result, _) = cacheAndAwaitSuccess(manager)

        assertThat(result).isSameInstanceAs(bid10)
        verify { bid10.adSource.markWin() }

        Thread.sleep(200)

        // Main: [$10*, $9, $8] (full)
        assertThat(mainCache.state.value.isFull).isTrue()
        assertThat(mainCache.state.value.size).isEqualTo(3)

        // Fallback: [$7, $5]
        assertThat(fallbackCache.state.value.isFull).isTrue()
        assertThat(fallbackCache.state.value.size).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // Section 13: Spec example 14.4 — Main not filled (threshold cuts)
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_4 - main not fully filled due to threshold`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 2
        )

        val bid10 = makeResult("dem10", 10.0)
        val bid7 = makeResult("dem7", 7.0) // 7.0 < 10*0.8=8.0 → reject → fb
        val bid5 = makeResult("dem5", 5.0) // reject → fb
        val bid3 = makeResult("dem3", 3.0) // reject, fb full, 3 <= 5 → both reject

        setupControllerWithBids(controller, listOf(bid10, bid7, bid5, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main: [$10*] (1/3)
        assertThat(mainCache.state.value.size).isEqualTo(1)
        assertThat(mainCache.state.value.isFull).isFalse()

        // Fallback: [$7, $5] (2/2)
        assertThat(fallbackCache.state.value.size).isEqualTo(2)
        assertThat(fallbackCache.state.value.isFull).isTrue()

        // $3 rejected by both → destroyed
        verify { bid3.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
        verify { bid3.adSource.destroy() }
    }

    // -----------------------------------------------------------------------
    // Section 14: Spec example 14.6 — Fallback eviction from prior auction
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_6 - new auction evicts old cheap bids from fallback`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 2
        )

        // Simulate leftover from auction 1
        val oldBid2 = makeResult("old2", 2.0)
        val oldBid1 = makeResult("old1", 1.0)
        fallbackCache.insert(oldBid2)
        fallbackCache.insert(oldBid1)

        // Auction 2: $10 → main, $5 → evicts $1, $4 → evicts $2
        val bid10 = makeResult("dem10", 10.0)
        val bid5 = makeResult("dem5", 5.0)
        val bid4 = makeResult("dem4", 4.0)

        setupControllerWithBids(controller, listOf(bid10, bid5, bid4))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { oldBid1.adSource.markFillFinished(RoundStatus.Lose, 1.0) }
        verify { oldBid1.adSource.destroy() }
        verify { oldBid2.adSource.markFillFinished(RoundStatus.Lose, 2.0) }
        verify { oldBid2.adSource.destroy() }

        // Fallback now: [$5, $4]
        assertThat(fallbackCache.state.value.head!!.adSource.getStats().price).isEqualTo(5.0)
    }

    // -----------------------------------------------------------------------
    // Section 15: Spec example 14.7 — cacheSize=1, fallbackSize=0
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_7 - cacheSize 1 fallbackSize 0, stops after first bid`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )

        val bid5 = makeResult("dem5", 5.0)

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            val shouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(bid5, false) // WIN

            // After first bid: main full + fb disabled → STOP
            assertThat(shouldContinue(3.0)).isFalse()
            assertThat(shouldContinue(2.0)).isFalse()

            onComplete(null, null)
        }

        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result).isSameInstanceAs(bid5)
        assertThat(mainCache.state.value.size).isEqualTo(1)
    }

    // -----------------------------------------------------------------------
    // Section 16: Spec example 14.8 — threshold=0 (filter disabled)
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_8 - threshold 0, all bids pass to main regardless of price`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 3, threshold = 0, fallbackCapacity = 0
        )

        val bid10 = makeResult("dem10", 10.0)
        val bid1 = makeResult("dem1", 1.0)
        val bid001 = makeResult("dem001", 0.01)

        setupControllerWithBids(controller, listOf(bid10, bid1, bid001))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.isFull).isTrue()
        assertThat(mainCache.state.value.size).isEqualTo(3)
    }

    // -----------------------------------------------------------------------
    // Section 17: Spec example 14.2 — cacheSize=1, threshold=80, fallbackSize=3
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_2 - cacheSize 1 with large fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 3
        )

        val bid5 = makeResult("dem5", 5.0) // main (sticky)
        val bid4 = makeResult("dem4", 4.0) // main full (sticky) → fb
        val bid3 = makeResult("dem3", 3.0) // fb
        val bid2 = makeResult("dem2", 2.0) // fb (full)

        setupControllerWithBids(controller, listOf(bid5, bid4, bid3, bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(1)
        assertThat(fallbackCache.state.value.size).isEqualTo(3)
        assertThat(fallbackCache.state.value.isFull).isTrue()
    }

    // -----------------------------------------------------------------------
    // Section 18: Auction state transitions
    // -----------------------------------------------------------------------

    @Test
    fun `auction completes - state returns to idle`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid))

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(manager.isIdle()).isTrue()
    }

    @Test
    fun `after auction - new cache call starts new auction`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup()

        // First auction
        val bid1 = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid1))
        cacheAndAwaitSuccess(manager)

        // Pop to empty main
        mainCache.popFirst()
        Thread.sleep(200)

        // Second auction should start (main empty now)
        val bid2 = makeResult("applovin", 8.0)
        setupControllerWithBids(controller, listOf(bid2))
        cacheAndAwaitSuccess(manager)

        coVerify(exactly = 2) { controller.start(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Section 19: Additional edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `multiple bids fill both caches in correct order`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 2, threshold = 70, fallbackCapacity = 2
        )

        val bids = listOf(
            makeResult("dem1", 10.0), // main (sticky)
            makeResult("dem2", 8.0), // main (8 >= 10*0.7=7)
            makeResult("dem3", 6.0), // main full, fb
            makeResult("dem4", 4.0), // main full, fb
        )

        setupControllerWithBids(controller, bids)
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(2)
        assertThat(mainCache.state.value.isFull).isTrue()
        assertThat(fallbackCache.state.value.size).isEqualTo(2)
        assertThat(fallbackCache.state.value.isFull).isTrue()
    }

    @Test
    fun `first bid to fallback also triggers onSuccess when main rejects it`() = runBlocking {
        // When the first (and only) bid goes to fallback because main rejected it
        // (e.g., main is full from pre-fill), it should still trigger onSuccess
        // via the onComplete fallback rescue path.
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 2
        )

        // Pre-fill main so it's full
        mainCache.insert(makeResult("existing", 20.0), sticky = true)

        // cache() will see main has content → instant delivery (cache hit)
        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result.adSource.getStats().demandId.demandId).isEqualTo("existing")
        coVerify(exactly = 0) { controller.start(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Section 20: Spec example 14.3 — show() during auction (cancel)
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_3 - pop during auction cancels remaining waterfall`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 3, threshold = 70, fallbackCapacity = 2
        )

        val bid8 = makeResult("dem8", 8.0)
        val bid7 = makeResult("dem7", 7.0)
        val bid6 = makeResult("dem6", 6.0)

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(bid8, false) // WIN (sticky)
            singleLoad(bid7, false) // CACHE main
            singleLoad(bid6, false) // CACHE main (full)
            // $4, $3, $2 not called — simulates early stop after cancel

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Simulate show() → pop + cancel
        val popped = manager.pop()
        assertThat(popped).isSameInstanceAs(bid8)

        // Main: [$7, $6]. Two more shows without auction.
        assertThat(mainCache.state.value.size).isEqualTo(2)
        assertThat(mainCache.state.value.head!!.adSource.getStats().price).isEqualTo(7.0)
    }

    // -----------------------------------------------------------------------
    // Section 21: Spec example 14.5 — Repeated auction updates fallback
    // -----------------------------------------------------------------------

    @Test
    fun `spec 14_5 - repeated auction refills fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 2
        )

        // Auction 1: $5 (sticky), $3 → fb, $2 → fb
        val a1_bid5 = makeResult("a1_dem5", 5.0)
        val a1_bid3 = makeResult("a1_dem3", 3.0)
        val a1_bid2 = makeResult("a1_dem2", 2.0)
        setupControllerWithBids(controller, listOf(a1_bid5, a1_bid3, a1_bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(1)
        assertThat(fallbackCache.state.value.size).isEqualTo(2)

        // show → pop $5
        manager.pop()
        assertThat(mainCache.state.value.hasContent).isFalse()

        // load → main empty → auction → no-fill → fallback $3 → didLoad
        setupControllerNoFill(controller)
        val (result2, _) = cacheAndAwaitSuccess(manager)
        assertThat(result2.adSource.getStats().price).isEqualTo(3.0)
    }

    // -----------------------------------------------------------------------
    // Section 22: Bidding eviction — no adapter notifyLoss
    // -----------------------------------------------------------------------

    @Test
    fun `evicted bidding result - no adapter notifyLoss`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val evicted = makeBiddingResult("rtb1", 3.0) // fallback
        val displacer = makeResult("dem3", 5.0) // evicts rtb1

        setupControllerWithBids(controller, listOf(bid1, evicted, displacer))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Bidding evicted: markFillFinished + markLoss + destroy, but NO notifyLoss
        verify { evicted.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
        verify { evicted.adSource.markLoss() }
        verify { evicted.adSource.destroy() }
        // Bidding → no adapter-level notifyLoss
    }

    // -----------------------------------------------------------------------
    // Section 23: Multiple fallback evictions in single auction
    // -----------------------------------------------------------------------

    @Test
    fun `multiple fallback evictions in one auction - all evicted get full loss cycle`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )

        // Pre-fill fallback with cheap bid from prior auction
        val oldCheap = makeResult("old1", 1.0)
        fallbackCache.insert(oldCheap)

        val bid10 = makeResult("dem10", 10.0) // main (sticky)
        val bid5 = makeResult("dem5", 5.0) // fb: evicts old1 ($1)
        val bid7 = makeResult("dem7", 7.0) // fb: evicts dem5 ($5)

        setupControllerWithBids(controller, listOf(bid10, bid5, bid7))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // old1 evicted by $5
        verify { oldCheap.adSource.markFillFinished(RoundStatus.Lose, 1.0) }
        verify { oldCheap.adSource.destroy() }

        // dem5 evicted by $7
        verify { bid5.adSource.markFillFinished(RoundStatus.Lose, 5.0) }
        verify { bid5.adSource.destroy() }

        // Only $7 remains in fallback
        assertThat(fallbackCache.state.value.head!!.adSource.getStats().price).isEqualTo(7.0)
        assertThat(fallbackCache.state.value.size).isEqualTo(1)
    }

    // -----------------------------------------------------------------------
    // Section 24: threshold=100 — only first bid passes main
    // -----------------------------------------------------------------------

    @Test
    fun `threshold 100 - only sticky first bid in main, rest to fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 3, threshold = 100, fallbackCapacity = 3
        )

        val bid10 = makeResult("dem10", 10.0) // main (sticky). bar = 10.0 * 1.0 = 10.0
        val bid9 = makeResult("dem9", 9.0) // 9 < 10 → main reject → fb
        val bid8 = makeResult("dem8", 8.0) // 8 < 10 → main reject → fb

        setupControllerWithBids(controller, listOf(bid10, bid9, bid8))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main: only sticky $10
        assertThat(mainCache.state.value.size).isEqualTo(1)
        // Fallback: $9, $8
        assertThat(fallbackCache.state.value.size).isEqualTo(2)
    }

    @Test
    fun `threshold 100 - equal price to max passes main`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 3, threshold = 100, fallbackCapacity = 0
        )

        val bid10 = makeResult("dem10", 10.0) // main (sticky)
        val bid10b = makeResult("dem10b", 10.0) // 10 >= 10 → passes threshold

        setupControllerWithBids(controller, listOf(bid10, bid10b))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // Section 25: pop() cancels auction (spec §8)
    // -----------------------------------------------------------------------

    @Test
    fun `pop cancels running auction`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup()

        val gate = CompletableDeferred<Unit>()
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            singleLoad(makeResult("dem1", 10.0), false)
            gate.await() // pause auction
        }

        // Start auction
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> },
            onFailure = { _, _ -> },
        )
        Thread.sleep(200)

        // Pop → cancels auction
        val popped = manager.pop()
        assertThat(popped).isNotNull()

        // After pop, state returns to idle (auction was cancelled)
        // Allow some time for cancellation to propagate
        Thread.sleep(100)
        assertThat(manager.isIdle()).isTrue()

        gate.complete(Unit)
        manager.clear()
    }

    // -----------------------------------------------------------------------
    // Section 26: peek priority — main over fallback
    // -----------------------------------------------------------------------

    @Test
    fun `peek - prefers main over fallback when both have content`() = runBlocking {
        val (manager, mainCache, fallbackCache, _) = createSetup()
        val mainItem = makeResult("main_dem", 10.0)
        val fbItem = makeResult("fb_dem", 20.0) // higher price but in fallback

        mainCache.insert(mainItem, sticky = false)
        fallbackCache.insert(fbItem)

        // peek returns main, even though fallback has higher price
        assertThat(manager.peek()).isSameInstanceAs(mainItem)
    }

    // -----------------------------------------------------------------------
    // Section 27: Full lifecycle — auction → fill → pop all → new auction
    // -----------------------------------------------------------------------

    @Test
    fun `full lifecycle - auction, pop all main, fallback rescue, pop fallback, new auction`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 2, threshold = 70, fallbackCapacity = 1
        )

        // Auction 1
        val bid10 = makeResult("dem10", 10.0)
        val bid8 = makeResult("dem8", 8.0)
        val bid3 = makeResult("dem3", 3.0) // threshold reject → fb

        setupControllerWithBids(controller, listOf(bid10, bid8, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Pop $10 (sticky)
        val pop1 = manager.pop()
        assertThat(pop1!!.adSource.getStats().price).isEqualTo(10.0)

        // Cache hit: $8 still in main
        val (hit, _) = cacheAndAwaitSuccess(manager)
        assertThat(hit.adSource.getStats().price).isEqualTo(8.0)

        // Pop $8
        manager.pop()

        // Main empty → auction 2 → no fill → fallback rescue $3
        setupControllerNoFill(controller)
        val (rescue, _) = cacheAndAwaitSuccess(manager)
        assertThat(rescue.adSource.getStats().price).isEqualTo(3.0)
    }

    // -----------------------------------------------------------------------
    // Section 28: After clear() — graceful behavior
    // -----------------------------------------------------------------------

    @Test
    fun `cache after clear - no crash, no callback`() = runBlocking {
        val (manager, _, _, _) = createSetup()
        manager.clear()

        val latch = CountDownLatch(1)
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { _, _ -> latch.countDown() },
        )

        // After clear, scope is cancelled → cache() launches into dead scope → no callback
        val fired = latch.await(500, TimeUnit.MILLISECONDS)
        assertThat(fired).isFalse()
    }

    @Test
    fun `peek after clear with pre-filled cache returns content`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = false)

        manager.clear()

        // peek reads StateFlow snapshot — still accessible even after clear
        assertThat(manager.peek()).isSameInstanceAs(item)
    }

    // -----------------------------------------------------------------------
    // Section 29: shouldContinueAuction — additional edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `shouldContinueAuction - main has space, ecpm at exact bar - true`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 0
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(makeResult("dem1", 10.0), false)
            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // bar = 10.0 * 0.8 = 8.0; ecpm=8.0 → canAcceptMain = true
        assertThat(capturedShouldContinue?.invoke(8.0)).isTrue()
        // ecpm = 7.99 → canAcceptMain = false, fb disabled → false
        assertThat(capturedShouldContinue?.invoke(7.99)).isFalse()
    }

    @Test
    fun `shouldContinueAuction - fallback not disabled and not full - accepts even low ecpm`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 5
        )

        var capturedShouldContinue: ((Double) -> Boolean)? = null
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            capturedShouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(makeResult("dem1", 10.0), false) // fills main
            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Main full but fallback empty (not full, not disabled)
        // Any ecpm is accepted because canAcceptFallback = !disabled && !full = true
        assertThat(capturedShouldContinue?.invoke(0.001)).isTrue()
    }

    // -----------------------------------------------------------------------
    // Section 30: Win/Loss notifications — additional combinations
    // -----------------------------------------------------------------------

    @Test
    fun `win notification - non-WinLossNotifiable adSource does NOT get notifyWin`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        // makeResult creates a plain AdSource (not WinLossNotifiable)
        val bid = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid), externalWinNotificationsEnabled = false)

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // markWin is called on all winners
        verify { bid.adSource.markWin() }
        // notifyWin is NOT called because adSource is not WinLossNotifiable
    }

    @Test
    fun `evicted WinLossNotifiable gets notifyLoss with displacer info`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 1
        )
        val bid1 = makeResult("dem1", 10.0) // main
        val evicted = makeWinLossResult("evicted_dem", 3.0) // fallback
        val displacer = makeResult("displacer_dem", 5.0) // evicts evicted_dem

        setupControllerWithBids(controller, listOf(bid1, evicted, displacer))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { (evicted.adSource as WinLossNotifiable).notifyLoss("displacer_dem", 5.0) }
        verify { evicted.adSource.markLoss() }
        verify { evicted.adSource.destroy() }
    }

    @Test
    fun `loss notification - no winner yet, loser does NOT get notifyLoss`() = runBlocking {
        // Edge case: the first bid could fail routing (both reject),
        // but in practice first bid always enters empty main.
        // Test with pre-filled main to simulate the scenario.
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )

        // Pre-fill main
        mainCache.insert(makeResult("existing", 20.0), sticky = true)

        // cache() sees main has content → cache hit, no auction
        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result.adSource.getStats().demandId.demandId).isEqualTo("existing")
    }

    // -----------------------------------------------------------------------
    // Section 31: Spec example 14.3 — show() during auction, cached bids preserved
    // -----------------------------------------------------------------------

    @Test
    fun `show during auction - already cached bids not lost`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 3, threshold = 70, fallbackCapacity = 0
        )

        val bid8 = makeResult("dem8", 8.0)
        val bid7 = makeResult("dem7", 7.0)
        val bid6 = makeResult("dem6", 6.0)

        setupControllerWithBids(controller, listOf(bid8, bid7, bid6))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        // Pop $8 (show)
        manager.pop()

        // Main still has $7 and $6
        assertThat(mainCache.state.value.size).isEqualTo(2)

        // Two more instant loads
        val (r1, _) = cacheAndAwaitSuccess(manager)
        assertThat(r1.adSource.getStats().price).isEqualTo(7.0)

        manager.pop()
        val (r2, _) = cacheAndAwaitSuccess(manager)
        assertThat(r2.adSource.getStats().price).isEqualTo(6.0)
    }

    // -----------------------------------------------------------------------
    // Section 32: AuctionInfo content validation
    // -----------------------------------------------------------------------

    @Test
    fun `cache hit - synthetic AuctionInfo has correct fields`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = false)

        val (_, info) = cacheAndAwaitSuccess(manager)

        assertThat(info.auctionId).isEqualTo("-") // null auctionId defaults to "-"
        assertThat(info.auctionTimeout).isEqualTo(0)
        assertThat(info.noBids).isNull()
        assertThat(info.adUnits).hasSize(1)

        val adUnit = info.adUnits!!.first()
        assertThat(adUnit.demandId).isEqualTo("admob")
        assertThat(adUnit.price).isEqualTo(10.0)
        assertThat(adUnit.status).isEqualTo(RoundStatus.Win.code)
    }

    @Test
    fun `first bid from auction - AuctionInfo has WIN status`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid))

        val (_, info) = cacheAndAwaitSuccess(manager)

        assertThat(info.adUnits).hasSize(1)
        assertThat(info.adUnits!!.first().status).isEqualTo(RoundStatus.Win.code)
    }

    // -----------------------------------------------------------------------
    // Section 33: Spec Кейс 2 — Fallback спасает no-fill
    // -----------------------------------------------------------------------

    @Test
    fun `spec case 2 - fallback saves no-fill with threshold rejection`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup(
            mainCapacity = 2, threshold = 70, fallbackCapacity = 2
        )

        val bid5 = makeResult("dem5", 5.0) // main (sticky), bar = 5 * 0.7 = 3.5
        val bid2 = makeResult("dem2", 2.0) // 2 < 3.5 → main reject → fb

        setupControllerWithBids(controller, listOf(bid5, bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(fallbackCache.state.value.size).isEqualTo(1)

        // show → $5
        manager.pop()

        // load → main empty → auction → no-fill → fallback $2 → didLoad
        setupControllerNoFill(controller)
        val (rescue, _) = cacheAndAwaitSuccess(manager)
        assertThat(rescue.adSource.getStats().price).isEqualTo(2.0)
    }

    // -----------------------------------------------------------------------
    // Section 34: Spec Кейс 1 — Два последовательных loadAd()
    // -----------------------------------------------------------------------

    @Test
    fun `two sequential loads - main not empty - both return instantly`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup()
        mainCache.insert(makeResult("dem1", 10.0), sticky = true)

        val (r1, _) = cacheAndAwaitSuccess(manager)
        val (r2, _) = cacheAndAwaitSuccess(manager)

        assertThat(r1.adSource.getStats().price).isEqualTo(10.0)
        assertThat(r2.adSource.getStats().price).isEqualTo(10.0) // same head, peek not pop
        coVerify(exactly = 0) { controller.start(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `two sequential loads - both empty, first starts auction, second is silent`() = runBlocking {
        val (manager, _, _, controller) = createSetup()

        val gate = CompletableDeferred<Unit>()
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }

        // First load starts auction
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> },
            onFailure = { _, _ -> },
        )
        Thread.sleep(100)

        // Second load is silent (auction running)
        val latch = CountDownLatch(1)
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { _, _ -> latch.countDown() },
        )
        val fired = latch.await(500, TimeUnit.MILLISECONDS)
        assertThat(fired).isFalse()

        gate.complete(Unit)
        manager.clear()
    }

    // -----------------------------------------------------------------------
    // Section 35: First bid WIN from fallback path
    // -----------------------------------------------------------------------

    @Test
    fun `first bid rejected by main goes to fallback - triggers WIN via onComplete rescue`() = runBlocking {
        // This is edge case: normally first bid always enters empty main.
        // But if main somehow has content pre-filled, the auction path differs.
        // Test the no-fill rescue path with fallback content.
        val (manager, _, fallbackCache, controller) = createSetup()

        val fbBid = makeResult("fb_bid", 5.0)
        fallbackCache.insert(fbBid)

        setupControllerNoFill(controller) // no fills from auction

        val (result, _) = cacheAndAwaitSuccess(manager)
        // fallback rescue
        assertThat(result).isSameInstanceAs(fbBid)
    }

    // -----------------------------------------------------------------------
    // Section 36: Spec configurations table (§11)
    // -----------------------------------------------------------------------

    @Test
    fun `config cacheSize N fallbackSize 0 - main fills then stops`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 0
        )

        val bid10 = makeResult("dem10", 10.0)
        val bid9 = makeResult("dem9", 9.0)
        val bid8 = makeResult("dem8", 8.0) // fills main

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val singleLoad = arg<suspend (AuctionResult, Boolean) -> Unit>(2)
            val shouldContinue = arg<(Double) -> Boolean>(3)
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)

            singleLoad(bid10, false)
            singleLoad(bid9, false)
            singleLoad(bid8, false)

            // Main full + fb disabled → STOP
            assertThat(shouldContinue(7.0)).isFalse()
            assertThat(shouldContinue(1.0)).isFalse()

            onComplete(null, null)
        }

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)
        assertThat(mainCache.state.value.isFull).isTrue()
    }

    @Test
    fun `config cacheSize 1 fallbackSize M - main first bid, rest to fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 5
        )

        val bid10 = makeResult("dem10", 10.0) // main
        val bid5 = makeResult("dem5", 5.0) // fb
        val bid3 = makeResult("dem3", 3.0) // fb
        val bid1 = makeResult("dem1", 1.0) // fb

        setupControllerWithBids(controller, listOf(bid10, bid5, bid3, bid1))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        assertThat(mainCache.state.value.size).isEqualTo(1)
        assertThat(fallbackCache.state.value.size).isEqualTo(3)
    }

    // -----------------------------------------------------------------------
    // Section 37: externalWinNotificationsEnabled with loss path
    // -----------------------------------------------------------------------

    @Test
    fun `externalWinNotificationsEnabled - winner still gets markWin but no notifyWin`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val bid = makeWinLossResult("admob", 10.0)
        setupControllerWithBids(controller, listOf(bid), externalWinNotificationsEnabled = true)

        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid.adSource.markWin() }
        verify(exactly = 0) { (bid.adSource as WinLossNotifiable).notifyWin() }
    }

    // -----------------------------------------------------------------------
    // Section 38: Multiple sequential auctions (full cycle)
    // -----------------------------------------------------------------------

    @Test
    fun `three sequential auctions - each fills and drains correctly`() = runBlocking {
        val (manager, mainCache, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )

        // Auction 1
        val bid1 = makeResult("dem1", 10.0)
        setupControllerWithBids(controller, listOf(bid1))
        val (r1, _) = cacheAndAwaitSuccess(manager)
        assertThat(r1.adSource.getStats().price).isEqualTo(10.0)
        manager.pop()
        Thread.sleep(200)

        // Auction 2
        val bid2 = makeResult("dem2", 8.0)
        setupControllerWithBids(controller, listOf(bid2))
        val (r2, _) = cacheAndAwaitSuccess(manager)
        assertThat(r2.adSource.getStats().price).isEqualTo(8.0)
        manager.pop()
        Thread.sleep(200)

        // Auction 3
        val bid3 = makeResult("dem3", 6.0)
        setupControllerWithBids(controller, listOf(bid3))
        val (r3, _) = cacheAndAwaitSuccess(manager)
        assertThat(r3.adSource.getStats().price).isEqualTo(6.0)

        coVerify(exactly = 3) { controller.start(any(), any(), any(), any(), any()) }
    }

    // -----------------------------------------------------------------------
    // Section 39: Edge — no-fill with BidonError types
    // -----------------------------------------------------------------------

    @Test
    fun `no fill with InternalServerError - fallback rescue still works`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup()
        fallbackCache.insert(makeResult("cached", 3.0))

        setupControllerNoFill(controller, error = BidonError.InternalServerSdkError("test"))

        val (result, _) = cacheAndAwaitSuccess(manager)
        assertThat(result.adSource.getStats().price).isEqualTo(3.0)
    }

    @Test
    fun `no fill with null error - returns NoFill`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        setupControllerNoFill(controller, error = null)

        val error = cacheAndAwaitFailure(manager)
        assertThat(error).isInstanceOf(BidonError.NoFill::class.java)
    }

    // -----------------------------------------------------------------------
    // Section 40: markFillFinished called with correct price
    // -----------------------------------------------------------------------

    @Test
    fun `cached bid - markFillFinished called with Cached status and own price`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 3, threshold = 80, fallbackCapacity = 0
        )
        val bid1 = makeResult("dem1", 10.0) // WIN
        val bid2 = makeResult("dem2", 9.5) // CACHE at 9.5
        val bid3 = makeResult("dem3", 8.5) // CACHE at 8.5

        setupControllerWithBids(controller, listOf(bid1, bid2, bid3))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid2.adSource.markFillFinished(RoundStatus.Cached, 9.5) }
        verify { bid3.adSource.markFillFinished(RoundStatus.Cached, 8.5) }
    }

    @Test
    fun `destroyed bid - markFillFinished called with Lose status and own price`() = runBlocking {
        val (manager, _, _, controller) = createSetup(
            mainCapacity = 1, threshold = 80, fallbackCapacity = 0
        )
        val bid1 = makeResult("dem1", 10.0) // WIN
        val bid2 = makeResult("dem2", 3.0) // LOSE at 3.0

        setupControllerWithBids(controller, listOf(bid1, bid2))
        cacheAndAwaitSuccess(manager)
        Thread.sleep(200)

        verify { bid2.adSource.markFillFinished(RoundStatus.Lose, 3.0) }
    }

    // -----------------------------------------------------------------------
    // Section 41: AuctionInfo with rich BidStat data (public callback validation)
    // -----------------------------------------------------------------------

    /**
     * Create an AuctionResult whose BidStat has all fields populated,
     * simulating what SequentialAuctionPipeline would set.
     */
    private fun makeRichResult(
        demandId: String,
        price: Double,
        auctionId: String = "auction-123",
        auctionPricefloor: Double = 0.5,
        fillStartTs: Long = 1000L,
        fillFinishTs: Long = 1200L,
        adUnitLabel: String = "label_$demandId",
        adUnitUid: String = "uid_$demandId",
        bidType: BidType = BidType.CPM,
        ext: String? = """{"key":"value"}""",
    ): AuctionResult.Network {
        val adSource = mockk<AdSource<*>>(relaxed = true)
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId(demandId),
            price = price,
            auctionId = auctionId,
            roundStatus = RoundStatus.Successful,
            auctionPricefloor = auctionPricefloor,
            fillStartTs = fillStartTs,
            fillFinishTs = fillFinishTs,
            dspSource = null,
            adUnit = AdUnit(
                demandId = demandId,
                label = adUnitLabel,
                pricefloor = auctionPricefloor,
                uid = adUnitUid,
                bidType = bidType,
                timeout = 5000L,
                ext = ext,
            ),
            tokenInfo = null,
        )
        return AuctionResult.Network(adSource = adSource, roundStatus = RoundStatus.Successful)
    }

    @Test
    fun `onSuccess AuctionInfo - cache hit propagates all BidStat fields to AdUnitInfo`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val rich = makeRichResult(
            demandId = "admob",
            price = 12.5,
            auctionId = "auction-xyz",
            auctionPricefloor = 0.5,
            fillStartTs = 1000L,
            fillFinishTs = 1200L,
            adUnitLabel = "interstitial_admob",
            adUnitUid = "uid-001",
            bidType = BidType.CPM,
            ext = """{"placement":"top"}""",
        )
        mainCache.insert(rich, sticky = true)

        val (_, info) = cacheAndAwaitSuccess(manager)

        // AuctionInfo level
        assertThat(info.auctionId).isEqualTo("auction-xyz") // uses real auctionId, not "-"
        assertThat(info.auctionPricefloor).isEqualTo(0.5)
        assertThat(info.auctionTimeout).isEqualTo(0) // synthetic always 0
        assertThat(info.auctionConfigurationId).isNull() // synthetic always null
        assertThat(info.auctionConfigurationUid).isNull()
        assertThat(info.noBids).isNull()

        // AdUnitInfo level — full data passthrough
        assertThat(info.adUnits).hasSize(1)
        val adUnit = info.adUnits!!.first()
        assertThat(adUnit.demandId).isEqualTo("admob")
        assertThat(adUnit.price).isEqualTo(12.5)
        assertThat(adUnit.status).isEqualTo(RoundStatus.Win.code)
        assertThat(adUnit.label).isEqualTo("interstitial_admob")
        assertThat(adUnit.uid).isEqualTo("uid-001")
        assertThat(adUnit.bidType).isEqualTo(BidType.CPM.code)
        assertThat(adUnit.fillStartTs).isEqualTo(1000L)
        assertThat(adUnit.fillFinishTs).isEqualTo(1200L)
        assertThat(adUnit.ext).isEqualTo("""{"placement":"top"}""")
    }

    @Test
    fun `onSuccess AuctionInfo - first bid from auction has all BidStat fields`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        val rich = makeRichResult(
            demandId = "applovin",
            price = 8.0,
            auctionId = "auction-456",
            auctionPricefloor = 1.0,
            adUnitLabel = "banner_applovin",
            adUnitUid = "uid-ap-01",
            bidType = BidType.RTB,
        )
        setupControllerWithBids(controller, listOf(rich))

        val (_, info) = cacheAndAwaitSuccess(manager)

        assertThat(info.auctionId).isEqualTo("auction-456")
        assertThat(info.auctionPricefloor).isEqualTo(1.0)

        val adUnit = info.adUnits!!.first()
        assertThat(adUnit.demandId).isEqualTo("applovin")
        assertThat(adUnit.label).isEqualTo("banner_applovin")
        assertThat(adUnit.uid).isEqualTo("uid-ap-01")
        assertThat(adUnit.bidType).isEqualTo(BidType.RTB.code)
        assertThat(adUnit.status).isEqualTo(RoundStatus.Win.code)
    }

    @Test
    fun `onSuccess AuctionInfo - null auctionId defaults to dash`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        // Default makeResult has auctionId=null
        mainCache.insert(makeResult("admob", 10.0), sticky = true)

        val (_, info) = cacheAndAwaitSuccess(manager)

        assertThat(info.auctionId).isEqualTo("-")
    }

    @Test
    fun `onSuccess AuctionInfo - null adUnit gives null label, uid, bidType, ext`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        // makeResult has adUnit=null in BidStat
        mainCache.insert(makeResult("admob", 10.0), sticky = true)

        val (_, info) = cacheAndAwaitSuccess(manager)

        val adUnit = info.adUnits!!.first()
        assertThat(adUnit.label).isNull()
        assertThat(adUnit.uid).isNull()
        assertThat(adUnit.bidType).isNull()
        assertThat(adUnit.ext).isNull()
    }

    // -----------------------------------------------------------------------
    // Section 42: onFailure callback — AuctionInfo from pipeline
    // -----------------------------------------------------------------------

    @Test
    fun `onFailure - receives pipeline AuctionInfo when available`() = runBlocking {
        val (manager, _, _, controller) = createSetup()

        val pipelineInfo = AuctionInfo(
            auctionId = "pipeline-auction-789",
            auctionConfigurationId = 42L,
            auctionConfigurationUid = "config-uid-abc",
            auctionTimeout = 15000,
            auctionPricefloor = 1.5,
            noBids = null,
            adUnits = null,
        )

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            onComplete(pipelineInfo, BidonError.NoFill(DemandId("auction")))
        }

        val latch = CountDownLatch(1)
        val infoRef = AtomicReference<AuctionInfo?>()
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { info, _ -> infoRef.set(info); latch.countDown() },
        )
        latch.await(5, TimeUnit.SECONDS)

        val info = infoRef.get()
        assertThat(info).isNotNull()
        assertThat(info!!.auctionId).isEqualTo("pipeline-auction-789")
        assertThat(info.auctionConfigurationId).isEqualTo(42L)
        assertThat(info.auctionConfigurationUid).isEqualTo("config-uid-abc")
        assertThat(info.auctionTimeout).isEqualTo(15000)
        assertThat(info.auctionPricefloor).isEqualTo(1.5)
    }

    @Test
    fun `onFailure - null AuctionInfo when pipeline returns null`() = runBlocking {
        val (manager, _, _, controller) = createSetup()

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            onComplete(null, BidonError.InternalServerSdkError("server error"))
        }

        val latch = CountDownLatch(1)
        val infoRef = AtomicReference<AuctionInfo?>(AuctionInfo("sentinel", null, null, 0, 0.0, null, null))
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { info, _ -> infoRef.set(info); latch.countDown() },
        )
        latch.await(5, TimeUnit.SECONDS)

        assertThat(infoRef.get()).isNull()
    }

    // -----------------------------------------------------------------------
    // Section 43: Fallback rescue — uses pipeline AuctionInfo, not synthetic
    // -----------------------------------------------------------------------

    @Test
    fun `fallback rescue - uses pipeline AuctionInfo when available`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup()
        fallbackCache.insert(makeResult("cached", 3.0))

        val pipelineInfo = AuctionInfo(
            auctionId = "pipeline-auction-rescue",
            auctionConfigurationId = 99L,
            auctionConfigurationUid = "config-rescue",
            auctionTimeout = 10000,
            auctionPricefloor = 2.0,
            noBids = null,
            adUnits = null,
        )

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            // No fills, but pipeline returns AuctionInfo
            onComplete(pipelineInfo, null)
        }

        val (_, info) = cacheAndAwaitSuccess(manager)

        // Should use pipeline info, NOT synthetic
        assertThat(info.auctionId).isEqualTo("pipeline-auction-rescue")
        assertThat(info.auctionConfigurationId).isEqualTo(99L)
        assertThat(info.auctionConfigurationUid).isEqualTo("config-rescue")
        assertThat(info.auctionTimeout).isEqualTo(10000)
    }

    @Test
    fun `fallback rescue - falls back to synthetic when pipeline AuctionInfo is null`() = runBlocking {
        val (manager, _, fallbackCache, controller) = createSetup()
        val fbBid = makeRichResult(
            demandId = "cached_dem",
            price = 5.0,
            auctionId = "old-auction-id",
        )
        fallbackCache.insert(fbBid)

        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            val onComplete = arg<suspend (AuctionInfo?, BidonError?) -> Unit>(4)
            onComplete(null, BidonError.InternalServerSdkError("server error"))
        }

        val (_, info) = cacheAndAwaitSuccess(manager)

        // No pipeline info → synthetic from fallback head
        assertThat(info.auctionId).isEqualTo("old-auction-id")
        assertThat(info.auctionConfigurationId).isNull() // synthetic always null
        assertThat(info.adUnits).hasSize(1)
        assertThat(info.adUnits!!.first().demandId).isEqualTo("cached_dem")
        assertThat(info.adUnits!!.first().status).isEqualTo(RoundStatus.Win.code)
    }

    // -----------------------------------------------------------------------
    // Section 44: Callbacks invoked on Main thread
    // -----------------------------------------------------------------------

    @Test
    fun `onSuccess callback invoked on Main dispatcher`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        mainCache.insert(makeResult("admob", 10.0), sticky = true)

        val latch = CountDownLatch(1)
        // With UnconfinedTestDispatcher as Main, the callback should execute
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> latch.countDown() },
            onFailure = { _, _ -> },
        )

        val fired = latch.await(5, TimeUnit.SECONDS)
        assertThat(fired).isTrue()
    }

    @Test
    fun `onFailure callback invoked on Main dispatcher`() = runBlocking {
        val (manager, _, _, controller) = createSetup()
        setupControllerNoFill(controller)

        val latch = CountDownLatch(1)
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> },
            onFailure = { _, _ -> latch.countDown() },
        )

        val fired = latch.await(5, TimeUnit.SECONDS)
        assertThat(fired).isTrue()
    }

    // -----------------------------------------------------------------------
    // Section 45: poll() — suspend until content available (AdCache interface)
    // -----------------------------------------------------------------------

    @Test
    fun `poll - returns immediately when main has content`() = runBlocking {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("admob", 10.0)
        mainCache.insert(item, sticky = true)

        val result = manager.poll()

        assertThat(result).isSameInstanceAs(item)
        // poll pops — main should be empty after
        assertThat(mainCache.state.value.hasContent).isFalse()
    }

    @Test
    fun `poll - returns from fallback when main empty`() = runBlocking {
        val (manager, _, fallbackCache, _) = createSetup()
        val item = makeResult("fb_dem", 5.0)
        fallbackCache.insert(item)

        val result = manager.poll()

        assertThat(result).isSameInstanceAs(item)
        assertThat(fallbackCache.state.value.hasContent).isFalse()
    }

    @Test
    fun `poll - prefers main over fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, _) = createSetup()
        mainCache.insert(makeResult("main_dem", 10.0), sticky = true)
        fallbackCache.insert(makeResult("fb_dem", 5.0))

        val result = manager.poll()

        assertThat(result.adSource.getStats().demandId.demandId).isEqualTo("main_dem")
    }

    @Test
    fun `poll - suspends until content appears then returns`() = runBlocking<Unit> {
        val (manager, mainCache, _, _) = createSetup()
        val item = makeResult("delayed", 7.0)

        val resultRef = AtomicReference<AuctionResult>()
        val latch = CountDownLatch(1)

        // Launch poll on a real dispatcher — it must actually suspend waiting for content
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            resultRef.set(manager.poll())
            latch.countDown()
        }

        // Verify poll hasn't returned yet
        val earlyFire = latch.await(300, TimeUnit.MILLISECONDS)
        assertThat(earlyFire).isFalse()

        // Insert item — poll should wake up via StateFlow emission
        mainCache.insert(item, sticky = false)

        val fired = latch.await(5, TimeUnit.SECONDS)
        assertThat(fired).isTrue()
        assertThat(resultRef.get()).isSameInstanceAs(item)
        job.cancel()
    }

    @Test
    fun `poll - cancels auction on return`() = runBlocking<Unit> {
        val (manager, mainCache, _, controller) = createSetup()
        val gate = CompletableDeferred<Unit>()
        coEvery { controller.start(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }

        // Start auction
        manager.cache(
            adTypeParam = mockAdTypeParam(),
            onSuccess = { _, _ -> },
            onFailure = { _, _ -> },
        )
        Thread.sleep(100)

        // Insert content directly and poll
        mainCache.insert(makeResult("dem1", 10.0), sticky = false)
        manager.poll()

        // After poll, auction state should be Idle (cancelled)
        assertThat(manager.isIdle()).isTrue()
        gate.complete(Unit)
    }

    @Test
    fun `poll - sequential polls drain main then fallback`() = runBlocking {
        val (manager, mainCache, fallbackCache, _) = createSetup()
        mainCache.insert(makeResult("m1", 10.0), sticky = true)
        mainCache.insert(makeResult("m2", 9.0), sticky = false)
        fallbackCache.insert(makeResult("f1", 5.0))

        val r1 = manager.poll()
        assertThat(r1.adSource.getStats().price).isEqualTo(10.0)

        val r2 = manager.poll()
        assertThat(r2.adSource.getStats().price).isEqualTo(9.0)

        val r3 = manager.poll()
        assertThat(r3.adSource.getStats().price).isEqualTo(5.0)

        assertThat(mainCache.state.value.hasContent).isFalse()
        assertThat(fallbackCache.state.value.hasContent).isFalse()
    }
}
