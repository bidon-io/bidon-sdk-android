package org.bidon.sdk.ads.cache.twolevel.auction

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.di.SimpleDiStorage
import org.bidon.sdk.utils.di.module
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
internal class SequentialAuctionPipelineTest {

    private lateinit var adaptersSource: AdaptersSource
    private lateinit var getTokens: GetTokensUseCase
    private lateinit var getAuctionRequest: GetAuctionRequestUseCase
    private lateinit var auctionStat: AuctionStat
    private lateinit var biddingConfig: BiddingConfig
    private lateinit var resultsCollector: ResultsCollector

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkLog()

        adaptersSource = mockk(relaxed = true)
        getTokens = mockk(relaxed = true)
        getAuctionRequest = mockk(relaxed = true)
        auctionStat = mockk(relaxed = true)
        biddingConfig = mockk(relaxed = true)
        resultsCollector = mockk(relaxed = true)

        every { biddingConfig.tokenTimeout } returns 5000L
        coEvery { getTokens.invoke(any(), any(), any()) } returns emptyMap()

        // Register ResultsCollector in DI so pipeline's get<ResultsCollector>() works
        SimpleDiStorage.instances.clear()
        module {
            factory<ResultsCollector> { resultsCollector }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SimpleDiStorage.instances.clear()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun createPipeline(): SequentialAuctionPipeline {
        return SequentialAuctionPipeline(
            adaptersSource = adaptersSource,
            getTokens = getTokens,
            getAuctionRequest = getAuctionRequest,
            auctionStat = auctionStat,
            biddingConfig = biddingConfig,
            adTypeLabel = "INTERSTITIAL",
        )
    }

    private fun makeAdUnit(
        demandId: String,
        pricefloor: Double,
        bidType: BidType = BidType.CPM,
        timeout: Long = 5000L,
    ) = AdUnit(
        demandId = demandId,
        label = demandId,
        pricefloor = pricefloor,
        uid = "uid_$demandId",
        bidType = bidType,
        timeout = timeout,
        ext = null,
    )

    private fun makeAd(price: Double, adUnit: AdUnit) = Ad(
        demandAd = DemandAd(AdType.Interstitial),
        price = price,
        auctionId = "auction_1",
        dsp = null,
        currencyCode = null,
        adUnit = adUnit,
    )

    private interface InterstitialAdSource : AdSource.Interstitial<AdAuctionParams>, AdProvider.Interstitial<AdAuctionParams>

    /** Adapter that implements both Network and AdProvider.Interstitial */
    private interface TestAdapter : Adapter.Network, AdProvider.Interstitial<AdAuctionParams>

    /**
     * Create a mock adapter + adSource that fills with given price.
     * Returns the adSource mock for verification.
     */
    private fun setupAdapter(
        demandId: String,
        fillPrice: Double,
        adUnit: AdUnit,
        bidType: BidType = BidType.CPM,
    ): AdSource.Interstitial<AdAuctionParams> {
        val adEventFlow = MutableSharedFlow<AdEvent>(replay = 1)
        val ad = makeAd(fillPrice, adUnit)

        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource.adEvent } returns adEventFlow
        every { adSource.getAd() } returns ad
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId(demandId),
            price = fillPrice,
            auctionId = "auction_1",
            roundStatus = RoundStatus.Successful,
            auctionPricefloor = 0.0,
            fillStartTs = null,
            fillFinishTs = null,
            dspSource = null,
            adUnit = adUnit,
            tokenInfo = null,
        )
        every { adSource.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))

        // When load() is called, emit Fill
        every { adSource.load(any()) } answers {
            adEventFlow.tryEmit(AdEvent.Fill(ad))
        }

        val mockAdapter = mockk<TestAdapter>(relaxed = true)
        every { mockAdapter.demandId } returns DemandId(demandId)
        every { mockAdapter.adapterInfo } returns mockk(relaxed = true)
        every { mockAdapter.interstitial() } returns adSource

        every { adaptersSource.adapters } returns setOf(mockAdapter)

        return adSource
    }

    private fun setupAdapterNoFill(
        demandId: String,
        adUnit: AdUnit,
    ): AdSource.Interstitial<AdAuctionParams> {
        val adEventFlow = MutableSharedFlow<AdEvent>(replay = 1)

        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource.adEvent } returns adEventFlow
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId(demandId),
            price = 0.0,
            auctionId = "auction_1",
            roundStatus = RoundStatus.NoFill,
            auctionPricefloor = 0.0,
            fillStartTs = null,
            fillFinishTs = null,
            dspSource = null,
            adUnit = adUnit,
            tokenInfo = null,
        )
        every { adSource.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))

        every { adSource.load(any()) } answers {
            adEventFlow.tryEmit(AdEvent.LoadFailed(BidonError.NoFill(DemandId(demandId))))
        }

        val mockAdapter = mockk<TestAdapter>(relaxed = true)
        every { mockAdapter.demandId } returns DemandId(demandId)
        every { mockAdapter.adapterInfo } returns mockk(relaxed = true)
        every { mockAdapter.interstitial() } returns adSource

        every { adaptersSource.adapters } returns setOf(mockAdapter)

        return adSource
    }

    private fun makeResponse(
        adUnits: List<AdUnit>,
        pricefloor: Double = 0.1,
        externalWinNotificationsEnabled: Boolean = false,
        auctionTimeout: Long = 30000L,
    ) = AuctionResponse(
        adUnits = adUnits,
        noBids = null,
        pricefloor = pricefloor,
        auctionId = "auction_1",
        auctionTimeout = auctionTimeout,
        auctionConfigurationId = 100L,
        auctionConfigurationUid = "uid_100",
        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
    )

    private fun mockAdTypeParam(pricefloor: Double = 0.1): AdTypeParam.Interstitial {
        return AdTypeParam.Interstitial(
            activity = mockk<Activity>(relaxed = true),
            pricefloor = pricefloor,
            auctionKey = "test_key",
        )
    }

    // -----------------------------------------------------------------------
    // markFillStarted
    // -----------------------------------------------------------------------

    @Test
    fun `markFillStarted - uses adUnit pricefloor, not auction pricefloor`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        val adSource = setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        val adTypeParam = mockAdTypeParam(pricefloor = 0.1)

        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = adTypeParam,
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        // markFillStarted should receive adUnit.pricefloor (5.0), not auction pricefloor (0.1)
        val adUnitSlot = slot<AdUnit>()
        val priceSlot = slot<Double>()
        verify { adSource.markFillStarted(capture(adUnitSlot), capture(priceSlot)) }
        assertThat(priceSlot.captured).isEqualTo(5.0)
        assertThat(adUnitSlot.captured.demandId).isEqualTo("admob")
    }

    // -----------------------------------------------------------------------
    // markFillFinished statuses
    // -----------------------------------------------------------------------

    @Test
    fun `fill - markFillFinished called with Successful and actual price`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        val adSource = setupAdapter("admob", fillPrice = 7.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        verify { adSource.markFillFinished(RoundStatus.Successful, 7.0) }
    }

    @Test
    fun `fill below pricefloor - markFillFinished with BelowPricefloor for CPM`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        val fillPrice = 0.05 // below auction pricefloor 0.1
        val adSource = setupAdapter("admob", fillPrice = fillPrice, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        verify { adSource.markFillFinished(RoundStatus.BelowPricefloor, fillPrice) }
    }

    @Test
    fun `no fill - markFillFinished called with error status`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        val adSource = setupAdapterNoFill("admob", adUnit)
        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        // LoadFailed with NoFill → asRoundStatus() → NoFill
        verify { adSource.markFillFinished(any(), isNull()) }
    }

    @Test
    fun `timeout - markFillFinished called with FillTimeoutReached`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0, timeout = 1) // 1ms timeout

        val adEventFlow = MutableSharedFlow<AdEvent>() // never emits
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource.adEvent } returns adEventFlow
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId("admob"),
            price = 0.0,
            auctionId = "auction_1",
            roundStatus = RoundStatus.NoFill,
            auctionPricefloor = 0.0,
            fillStartTs = null,
            fillFinishTs = null,
            dspSource = null,
            adUnit = adUnit,
            tokenInfo = null,
        )
        every { adSource.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))
        every { adSource.load(any()) } answers { /* never fills */ }

        val mockAdapter = mockk<TestAdapter>(relaxed = true)
        every { mockAdapter.demandId } returns DemandId("admob")
        every { mockAdapter.adapterInfo } returns mockk(relaxed = true)
        every { mockAdapter.interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(mockAdapter)

        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        verify { adSource.markFillFinished(RoundStatus.FillTimeoutReached, isNull()) }
    }

    // -----------------------------------------------------------------------
    // ResultsCollector flow
    // -----------------------------------------------------------------------

    @Test
    fun `resultsCollector - full lifecycle called in order`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            resultsCollector.startRound(0.1)
            resultsCollector.serverBiddingStarted()
            resultsCollector.serverBiddingFinished(any())
            resultsCollector.setNoBidInfo(any())
            resultsCollector.add(any())
            resultsCollector.getRoundResults()
            resultsCollector.clear()
        }
    }

    @Test
    fun `resultsCollector - add called for each adUnit`() = runTest {
        val adUnit1 = makeAdUnit("admob", pricefloor = 5.0)
        val adUnit2 = makeAdUnit("applovin", pricefloor = 3.0)

        val adEventFlow1 = MutableSharedFlow<AdEvent>(replay = 1)
        val adEventFlow2 = MutableSharedFlow<AdEvent>(replay = 1)
        val ad1 = makeAd(5.0, adUnit1)
        val ad2 = makeAd(3.0, adUnit2)

        val adSource1 = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource1.adEvent } returns adEventFlow1
        every { adSource1.getAd() } returns ad1
        every { adSource1.getStats() } returns BidStat(
            demandId = DemandId("admob"), price = 5.0, auctionId = "auction_1",
            roundStatus = RoundStatus.Successful, auctionPricefloor = 0.0,
            fillStartTs = null, fillFinishTs = null, dspSource = null,
            adUnit = adUnit1, tokenInfo = null,
        )
        every { adSource1.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))
        every { adSource1.load(any()) } answers { adEventFlow1.tryEmit(AdEvent.Fill(ad1)) }

        val adSource2 = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource2.adEvent } returns adEventFlow2
        every { adSource2.getAd() } returns ad2
        every { adSource2.getStats() } returns BidStat(
            demandId = DemandId("applovin"), price = 3.0, auctionId = "auction_1",
            roundStatus = RoundStatus.Successful, auctionPricefloor = 0.0,
            fillStartTs = null, fillFinishTs = null, dspSource = null,
            adUnit = adUnit2, tokenInfo = null,
        )
        every { adSource2.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))
        every { adSource2.load(any()) } answers { adEventFlow2.tryEmit(AdEvent.Fill(ad2)) }

        val adapter1 = mockk<TestAdapter>(relaxed = true)
        every { adapter1.demandId } returns DemandId("admob")
        every { adapter1.adapterInfo } returns mockk(relaxed = true)
        every { adapter1.interstitial() } returns adSource1

        val adapter2 = mockk<TestAdapter>(relaxed = true)
        every { adapter2.demandId } returns DemandId("applovin")
        every { adapter2.adapterInfo } returns mockk(relaxed = true)
        every { adapter2.interstitial() } returns adSource2

        every { adaptersSource.adapters } returns setOf(adapter1, adapter2)

        val response = makeResponse(listOf(adUnit1, adUnit2), pricefloor = 0.1)
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val fills = mutableListOf<AuctionResult>()
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { result, _ -> fills.add(result) },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        // Both fills delivered via singleLoadCompletion
        assertThat(fills).hasSize(2)

        // ResultsCollector.add called twice (once per fill)
        verify(exactly = 2) { resultsCollector.add(any()) }
    }

    // -----------------------------------------------------------------------
    // singleLoadCompletion
    // -----------------------------------------------------------------------

    @Test
    fun `singleLoadCompletion - fires immediately per fill with externalWin flag`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit), externalWinNotificationsEnabled = true)

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val completionArgs = mutableListOf<Pair<AuctionResult, Boolean>>()
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { result, extWin -> completionArgs.add(result to extWin) },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        assertThat(completionArgs).hasSize(1)
        assertThat(completionArgs[0].second).isTrue() // externalWinNotificationsEnabled
    }

    // -----------------------------------------------------------------------
    // shouldContinueAuction / pre-filter
    // -----------------------------------------------------------------------

    @Test
    fun `pre-filter stop - remaining marked LOSE in resultsCollector`() = runTest {
        val adUnit1 = makeAdUnit("admob", pricefloor = 10.0)
        val adUnit2 = makeAdUnit("applovin", pricefloor = 5.0)
        val adUnit3 = makeAdUnit("unity", pricefloor = 2.0)

        setupAdapter("admob", fillPrice = 10.0, adUnit = adUnit1)
        // adUnit2 and adUnit3 should not be loaded

        val response = makeResponse(listOf(adUnit1, adUnit2, adUnit3))
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        var callCount = 0
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { ecpm ->
                callCount++
                callCount <= 1 // stop after first
            },
            onComplete = { _, _ -> },
        )

        // add called: 1 fill (admob) + 2 remaining LOSE (applovin, unity)
        verify(exactly = 3) { resultsCollector.add(any()) }
    }

    // -----------------------------------------------------------------------
    // AuctionStat
    // -----------------------------------------------------------------------

    @Test
    fun `auctionStat - markAuctionStarted and sendAuctionStats called`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit))

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        verify { auctionStat.markAuctionStarted(any(), any()) }
        verify { auctionStat.sendAuctionStats(any(), any(), any()) }
    }

    @Test
    fun `server error - auctionStat sendAuctionStats still called`() = runTest {
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Server error"))

        val errorRef = AtomicReference<BidonError?>()
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, error -> errorRef.set(error as? BidonError) },
        )

        verify { auctionStat.markAuctionStarted(any(), any()) }
        verify { auctionStat.sendAuctionStats(any(), any(), any()) }
        assertThat(errorRef.get()).isInstanceOf(BidonError.InternalServerSdkError::class.java)
    }

    // -----------------------------------------------------------------------
    // onComplete
    // -----------------------------------------------------------------------

    @Test
    fun `no adUnits - onComplete with NoFill error`() = runTest {
        val response = makeResponse(emptyList())
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val errorRef = AtomicReference<BidonError?>()
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, error -> errorRef.set(error as? BidonError) },
        )

        assertThat(errorRef.get()).isInstanceOf(BidonError.NoFill::class.java)
    }

    @Test
    fun `all fills - onComplete with null error and auctionInfo`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit))

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val infoRef = AtomicReference<AuctionInfo?>()
        val errorRef = AtomicReference<BidonError?>()
        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { info, error ->
                infoRef.set(info)
                errorRef.set(error as? BidonError)
            },
        )

        assertThat(errorRef.get()).isNull()
        assertThat(infoRef.get()).isNotNull()
    }

    // -----------------------------------------------------------------------
    // applyParams
    // -----------------------------------------------------------------------

    @Test
    fun `applyParams - all stats fields set on adSource`() = runTest {
        val adUnit = makeAdUnit("admob", pricefloor = 5.0)
        val adSource = setupAdapter("admob", fillPrice = 5.0, adUnit = adUnit)
        val response = makeResponse(listOf(adUnit))

        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        verify { adSource.setStatisticAdType(any()) }
        verify { adSource.addRoundInfo(any(), any(), any()) }
        verify { adSource.addAuctionConfigurationId(100L) }
        verify { adSource.addAuctionConfigurationUid("uid_100") }
        verify { adSource.addExternalWinNotificationsEnabled(false) }
    }

    // -----------------------------------------------------------------------
    // RTB bidding type
    // -----------------------------------------------------------------------

    @Test
    fun `RTB fill below pricefloor - markFillFinished with Lose (not BelowPricefloor)`() = runTest {
        val adUnit = makeAdUnit("bidder", pricefloor = 5.0, bidType = BidType.RTB)
        val fillPrice = 0.05

        val adEventFlow = MutableSharedFlow<AdEvent>(replay = 1)
        val ad = makeAd(fillPrice, adUnit)

        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        every { adSource.adEvent } returns adEventFlow
        every { adSource.getAd() } returns ad
        every { adSource.getStats() } returns BidStat(
            demandId = DemandId("bidder"), price = fillPrice, auctionId = "auction_1",
            roundStatus = RoundStatus.Successful, auctionPricefloor = 0.0,
            fillStartTs = null, fillFinishTs = null, dspSource = null,
            adUnit = adUnit, tokenInfo = null,
        )
        every { adSource.getAuctionParam(any()) } returns Result.success(mockk(relaxed = true))
        every { adSource.load(any()) } answers { adEventFlow.tryEmit(AdEvent.Fill(ad)) }

        val mockAdapter = mockk<TestAdapter>(relaxed = true)
        every { mockAdapter.demandId } returns DemandId("bidder")
        every { mockAdapter.adapterInfo } returns mockk(relaxed = true)
        every { mockAdapter.interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(mockAdapter)

        val response = makeResponse(listOf(adUnit), pricefloor = 0.1)
        coEvery { getAuctionRequest.request(any(), any(), any(), any(), any()) } returns Result.success(response)

        val pipeline = createPipeline()
        pipeline.execute(
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = mockAdTypeParam(pricefloor = 0.1),
            singleLoadCompletion = { _, _ -> },
            shouldContinueAuction = { true },
            onComplete = { _, _ -> },
        )

        // RTB below pricefloor → Lose (not BelowPricefloor)
        verify { adSource.markFillFinished(RoundStatus.Lose, fillPrice) }
    }
}
