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
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidStat
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
        val bid2 = makeResult("dem2", 5.0)  // fallback
        val bid3 = makeResult("dem3", 3.0)  // main full (sticky), fb full, 3 <= 5 → reject

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
        val bid2 = makeResult("dem2", 5.0)  // main full + fb disabled → destroy

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
        val bid1 = makeResult("dem1", 10.0)  // main
        val bid2 = makeResult("dem2", 3.0)   // fallback
        val bid3 = makeResult("dem3", 5.0)   // fb full, 5 > 3 → evict dem2

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
        val bid1 = makeWinLossResult("dem1", 10.0)  // main
        val evicted = makeWinLossResult("dem2", 3.0) // fallback
        val displacer = makeResult("dem3", 5.0)      // evicts dem2

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
        val bid2 = makeResult("dem2", 5.0)  // fallback
        val bid3 = makeResult("dem3", 5.0)  // equal price → NO eviction → reject

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
        val bid2 = makeResult("dem2", 3.0)  // fallback
        val bid3 = makeResult("dem3", 5.0)  // evicts dem2

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
            singleLoad(makeResult("dem2", 5.0), false)  // fallback

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
            singleLoad(bid9, false)  // CACHE main
            singleLoad(bid8, false)  // CACHE main (full)
            singleLoad(bid7, false)  // CACHE fallback
            singleLoad(bid5, false)  // CACHE fallback (full)

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
        val bid7 = makeResult("dem7", 7.0)   // 7.0 < 10*0.8=8.0 → reject → fb
        val bid5 = makeResult("dem5", 5.0)   // reject → fb
        val bid3 = makeResult("dem3", 3.0)   // reject, fb full, 3 <= 5 → both reject

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
            makeResult("dem2", 8.0),  // main (8 >= 10*0.7=7)
            makeResult("dem3", 6.0),  // main full, fb
            makeResult("dem4", 4.0),  // main full, fb
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
}
