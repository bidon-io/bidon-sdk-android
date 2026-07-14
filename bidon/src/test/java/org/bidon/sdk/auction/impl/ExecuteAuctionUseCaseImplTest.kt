package org.bidon.sdk.auction.impl

import android.app.Activity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.impl.ExecuteAuctionUseCaseImpl
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.models.adapters.Process
import org.bidon.sdk.config.models.adapters.TestAdapter
import org.bidon.sdk.config.models.adapters.TestAdapterParameters
import org.bidon.sdk.config.models.adapters.TestBiddingAdapter
import org.bidon.sdk.config.models.auctions.impl.Admob
import org.bidon.sdk.config.models.auctions.impl.BidMachine
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.di.SimpleDiStorage
import org.bidon.sdk.utils.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
/**
 * Created by Bidon Team on 02/07/2024.
 */
internal class ExecuteAuctionUseCaseImplTest : ConcurrentTest() {

    private val auctionConfig by lazy {
        AuctionResponse(
            adUnits = listOf(
                AdUnit(
                    demandId = "admob",
                    label = "admob_banner",
                    pricefloor = 0.25,
                    uid = "12387837129819",
                    bidType = BidType.CPM,
                    timeout = 5000,
                    ext = jsonObject { "ad_unit_id" hasValue "ca-app-pub-3940256099942544/6300978111" }.toString(),
                ),
                AdUnit(
                    demandId = "bidmachine",
                    label = "bidmachine_banner",
                    uid = "32387837129819",
                    pricefloor = 0.0,
                    bidType = BidType.CPM,
                    timeout = 5000,
                    ext = null,
                )
            ),
            pricefloor = 0.01,
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = true,
            auctionTimeout = 10000L,
            noBids = listOf(
                AdUnit(
                    bidType = BidType.RTB,
                    demandId = "dem7",
                    label = "dem7_label",
                    pricefloor = 0.021,
                    uid = "123567",
                    timeout = 5000L,
                    ext = ""
                )
            )
        )
    }

    private val activity: Activity by lazy { mockk(relaxed = true) }
    private val adaptersSource: AdaptersSource = mockk()
    private val regulation: Regulation = mockk(relaxed = true)
    private val requestAdUnit: RequestAdUnitUseCase = mockk()

    private val testee: ExecuteAuctionUseCase by lazy {
        ExecuteAuctionUseCaseImpl(
            adaptersSource = adaptersSource,
            regulation = regulation,
            requestAdUnit = requestAdUnit,
        )
    }

    @Before
    fun before() {
        mockkObject(DeviceInfo)
        every { DeviceInfo.init(any()) } returns Unit
        DI.init(activity)
//        DI.setFactories()
        mockkLog()

        every { adaptersSource.adapters } returns setOf(
            TestAdapter(
                demandId = DemandId(Admob),
                testAdapterParameters = TestAdapterParameters(
                    bid = Process.Succeed,
                    fill = Process.Succeed
                )
            ),
            TestBiddingAdapter(
                demandId = DemandId(BidMachine),
                testAdapterParameters = TestAdapterParameters(
                    bid = Process.Succeed,
                    fill = Process.Succeed
                )
            ),
        )
    }

    @After
    fun after() {
        unmockkAll()
        SimpleDiStorage.instances.clear()
    }

    @Test
    fun `it should destroy started ad sources on destroyStartedAdSources`() = runTest {
        // PREPARE: one interstitial adapter whose ad source we can observe
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(Admob)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(adapter)

        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } returns AuctionResult.Network(adSource, RoundStatus.Successful)

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)

        // WHEN the auction runs and then is canceled
        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 10_000L,
            adUnits = listOf(
                AdUnit(
                    demandId = Admob,
                    label = "admob_interstitial",
                    pricefloor = 1.0,
                    uid = "1",
                    bidType = BidType.CPM,
                    timeout = 5000,
                    ext = null,
                )
            ),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )
        testee.destroyStartedAdSources()

        // THEN the started ad source is destroyed (BDN-1192)
        verify { adSource.destroy() }
    }

    @Test
    fun `it should report only the in-flight ad unit as FillTimeoutReached on auction timeout`() = runTest {
        // PREPARE: interstitial adapter whose request hangs, so the auction times out mid-request.
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(Admob)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(adapter)

        // The in-flight request actually started loading: fill started, not yet finished.
        val inFlightUnit = admobInterstitialAdUnit(uid = "1", pricefloor = 1.0)
        every { adSource.getStats() } returns bidStat(adUnit = inFlightUnit, fillStartTs = 100L, fillFinishTs = null)

        // Request never completes within the auction timeout
        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } coAnswers {
            delay(10_000L)
            AuctionResult.Network(adSource, RoundStatus.Successful)
        }

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)

        // WHEN the auction times out with a second ad unit still never requested
        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 1_000L,
            adUnits = listOf(
                inFlightUnit,
                admobInterstitialAdUnit(uid = "2", pricefloor = 2.0),
            ),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )

        // THEN the in-flight unit is finalized with real timing and reported as FillTimeoutReached
        verify { adSource.markFillFinished(RoundStatus.FillTimeoutReached, any()) }
        verify(exactly = 1) {
            resultsCollector.add(
                match {
                    it is AuctionResult.Network && it.roundStatus == RoundStatus.FillTimeoutReached
                }
            )
        }
        // AND never-requested units are not reported at all
        verify(exactly = 0) {
            resultsCollector.add(match { it is AuctionResult.AuctionFailed })
        }
    }

    @Test
    fun `it should not report an ad unit that never started loading on auction timeout`() = runTest {
        // PREPARE: request hangs before fill even started (no fillStartTs)
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(Admob)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(adapter)

        val unit = admobInterstitialAdUnit(uid = "1", pricefloor = 1.0)
        every { adSource.getStats() } returns bidStat(adUnit = unit, fillStartTs = null, fillFinishTs = null)

        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } coAnswers {
            delay(10_000L)
            AuctionResult.Network(adSource, RoundStatus.Successful)
        }

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)

        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 1_000L,
            adUnits = listOf(unit),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )

        // THEN nothing is reported (unit had not started loading → treated as never-requested)
        verify(exactly = 0) { resultsCollector.add(any()) }
    }

    @Test
    fun `it should report the in-flight RTB ad unit as Bidding FillTimeoutReached on auction timeout`() = runTest {
        // PREPARE: bidding adapter whose request hangs past the auction timeout
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(BidMachine)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(adapter)

        val rtbUnit = AdUnit(
            demandId = BidMachine,
            label = "bidmachine_interstitial",
            pricefloor = 1.0,
            uid = "1",
            bidType = BidType.RTB,
            timeout = 5000,
            ext = null,
        )
        every { adSource.getStats() } returns bidStat(
            demandId = DemandId(BidMachine),
            adUnit = rtbUnit,
            fillStartTs = 100L,
            fillFinishTs = null,
        )
        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } coAnswers {
            delay(10_000L)
            AuctionResult.Bidding(adSource, RoundStatus.Successful)
        }

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)

        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 1_000L,
            adUnits = listOf(rtbUnit),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )

        // THEN the in-flight RTB unit is reported as a Bidding FillTimeoutReached result
        verify { adSource.markFillFinished(RoundStatus.FillTimeoutReached, any()) }
        verify(exactly = 1) {
            resultsCollector.add(
                match {
                    it is AuctionResult.Bidding && it.roundStatus == RoundStatus.FillTimeoutReached
                }
            )
        }
    }

    @Test
    fun `it should report only the in-flight source when an earlier one already finished`() = runTest {
        // PREPARE: two ad units. The first finishes (NoFill), the second hangs until the timeout.
        val finishedSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val inFlightSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(Admob)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returnsMany
            listOf(finishedSource, inFlightSource)
        every { adaptersSource.adapters } returns setOf(adapter)

        val unit1 = admobInterstitialAdUnit(uid = "1", pricefloor = 1.0)
        val unit2 = admobInterstitialAdUnit(uid = "2", pricefloor = 2.0)
        every { finishedSource.getStats() } returns bidStat(adUnit = unit1, fillStartTs = 100L, fillFinishTs = 200L)
        every { inFlightSource.getStats() } returns bidStat(adUnit = unit2, fillStartTs = 300L, fillFinishTs = null)

        var call = 0
        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } coAnswers {
            call++
            if (call == 1) {
                AuctionResult.Network(finishedSource, RoundStatus.NoFill)
            } else {
                delay(10_000L)
                AuctionResult.Network(inFlightSource, RoundStatus.Successful)
            }
        }

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)

        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 1_000L,
            adUnits = listOf(unit1, unit2),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )

        // THEN only the in-flight source is finalized as timeout; the finished one is left untouched
        verify { inFlightSource.markFillFinished(RoundStatus.FillTimeoutReached, any()) }
        verify(exactly = 0) { finishedSource.markFillFinished(RoundStatus.FillTimeoutReached, any()) }
        verify(exactly = 1) {
            resultsCollector.add(
                match {
                    it is AuctionResult.Network && it.roundStatus == RoundStatus.FillTimeoutReached
                }
            )
        }
    }

    @Test
    fun `it should not report any timeout when the auction completes before its timeout`() = runTest {
        // PREPARE: request succeeds immediately, well within the auction timeout
        val adSource = mockk<AdSource.Interstitial<AdAuctionParams>>(relaxed = true)
        val adapter = mockk<Adapter>(moreInterfaces = arrayOf(AdProvider.Interstitial::class))
        every { adapter.demandId } returns DemandId(Admob)
        @Suppress("UNCHECKED_CAST")
        every { (adapter as AdProvider.Interstitial<AdAuctionParams>).interstitial() } returns adSource
        every { adaptersSource.adapters } returns setOf(adapter)

        val unit = admobInterstitialAdUnit(uid = "1", pricefloor = 1.0)
        every { adSource.getStats() } returns bidStat(adUnit = unit, fillStartTs = 100L, fillFinishTs = 200L)
        coEvery {
            requestAdUnit.invoke(any(), any(), any(), any())
        } returns AuctionResult.Network(adSource, RoundStatus.Successful)

        val resultsCollector = mockk<ResultsCollector>(relaxed = true)
        // A non-empty round result means the auction produced results, so the timeout branch is skipped
        every { resultsCollector.getRoundResults() } returns RoundResult.Results(
            biddingResult = BiddingResult.Idle,
            networkResults = listOf(AuctionResult.Network(adSource, RoundStatus.Successful)),
            pricefloor = 0.5,
            noBidsInfo = emptyList(),
        )

        testee.invoke(
            auctionId = "auctionId_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = false,
            demandAd = DemandAd(AdType.Interstitial),
            adTypeParam = AdTypeParam.Interstitial(activity, 0.5, null),
            pricefloor = 0.5,
            auctionTimeout = 10_000L,
            adUnits = listOf(unit),
            resultsCollector = resultsCollector,
            tokens = emptyMap(),
        )

        // THEN no FillTimeoutReached is produced on the happy path
        verify(exactly = 0) { adSource.markFillFinished(RoundStatus.FillTimeoutReached, any()) }
        verify(exactly = 0) {
            resultsCollector.add(match { it.roundStatus == RoundStatus.FillTimeoutReached })
        }
    }

    private fun admobInterstitialAdUnit(uid: String, pricefloor: Double) = AdUnit(
        demandId = Admob,
        label = "admob_interstitial_$uid",
        pricefloor = pricefloor,
        uid = uid,
        bidType = BidType.CPM,
        timeout = 5000,
        ext = null,
    )

    private fun bidStat(
        adUnit: AdUnit,
        fillStartTs: Long?,
        fillFinishTs: Long?,
        demandId: DemandId = DemandId(Admob),
    ) = BidStat(
        auctionId = "auctionId_123",
        demandId = demandId,
        roundStatus = null,
        price = adUnit.pricefloor,
        auctionPricefloor = 0.5,
        fillStartTs = fillStartTs,
        fillFinishTs = fillFinishTs,
        dspSource = null,
        adUnit = adUnit,
        tokenInfo = null,
    )

//    @Test
//    fun `it should conduct round`() = runTest {
//        // mockk results
//        coEvery {
//            conductNetworkAuction.invoke(
//                any(),
//                any(),
//                any(),
//                any(),
//                any(),
//                any(),
//                any(),
//                any(),
//                any()
//            )
//        } returns NetworksResult(
//            results = listOf(
//                CoroutineScope(mainDispatcherOverridden!!).async {
//                    AuctionResult.Network(
//                        adSource = mockk<AdSource<*>>(relaxed = true).also {
//                            every { it.demandId } returns DemandId(Admob)
//                            every { it.ad } returns Ad(
//                                demandAd = DemandAd(AdType.Interstitial),
//                                roundId = "r123",
//                                currencyCode = USD,
//                                dsp = null,
//                                price = 1.3,
//                                auctionId = "a123",
//                                adUnit = AdUnit(
//                                    demandId = "admob",
//                                    label = "admob_banner",
//                                    pricefloor = 0.25,
//                                    uid = "12387837129819",
//                                    bidType = BidType.CPM,
//                                    ext = jsonObject { "ad_unit_id" hasValue "ca-app-pub-3940256099942544/6300978111" }.toString(),
//                                )
//                            )
//                        },
//                        roundStatus = RoundStatus.Successful
//                    )
//                }
//            ),
//            remainingAdUnits = emptyList()
//        )
//        coEvery {
//            conductBiddingAuction.invoke(
//                context = any(),
//                biddingSources = any(),
//                adTypeParam = any(),
//                demandAd = any(),
//                bidfloor = any(),
//                auctionId = any(),
//                auctionConfigurationId = any(),
//                auctionConfigurationUid = any(),
//                adUnits = any(),
//                resultsCollector = any(),
//            )
//        } returns Unit
//
//        // it should conduct round with 2 results
//        val results = testee.invoke(
//            demandAd = DemandAd(AdType.Interstitial),
//            auctionResponse = auctionConfig,
//            adTypeParam = AdTypeParam.Interstitial(activity, 1.0, "auctionKey"),
//            pricefloor = 0.4,
//            adUnits = emptyList(),
//            resultsCollector = mockk(relaxed = true)
//        )
//        results
//            .onFailure {
//                it.printStackTrace()
//                error("unexpected")
//            }
//            .onSuccess {
//                assertThat(it).hasSize(4)
//                assertThat(it.filter { it.roundStatus == RoundStatus.UnknownAdapter }).hasSize(2)
//                assertThat(it.filter { it.roundStatus == RoundStatus.Successful }).hasSize(2)
//            }
//    }
}