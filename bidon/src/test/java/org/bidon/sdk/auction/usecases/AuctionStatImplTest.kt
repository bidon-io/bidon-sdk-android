package org.bidon.sdk.auction.usecases

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.BidsInfo
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.impl.MaxEcpmAuctionResolver
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.InterstitialRequest
import org.bidon.sdk.auction.usecases.impl.AuctionStatImpl
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.auction.usecases.models.ServerBiddingResult
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandStatus
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.StatsAdUnit
import org.bidon.sdk.stats.models.StatsRequestBody
import org.bidon.sdk.stats.models.StatsResult
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.di.SimpleDiStorage
import org.bidon.sdk.utils.json.jsonObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

internal class AuctionStatImplTest : ConcurrentTest() {

    private val statRequestUseCase: StatsRequestUseCase = mockk(relaxed = true)
    private val testee: AuctionStat by lazy {
        AuctionStatImpl(
            statsRequest = statRequestUseCase,
            resolver = MaxEcpmAuctionResolver
        )
    }

    @Before
    fun before() {
        mockkObject(DeviceInfo)
        every { DeviceInfo.init(any()) } returns Unit
        DI.init(mockk())
//        DI.setFactories()
        mockkLog()
    }

    @After
    fun after() {
        unmockkAll()
        SimpleDiStorage.instances.clear()
    }

    @Test
    fun `it should save results, DSP winner`() = runTest {
        // create mock data for Bid
        testee.markAuctionStarted(
            auctionId = "auction_id_123",
            adTypeParam = AdTypeParam.Interstitial(
                activity = mockk(),
                pricefloor = 1.1,
                auctionKey = null
            )
        )
        val actual = testee.addRoundResults(
            AuctionResult.Results(
                serverBiddingResult = ServerBiddingResult.FilledAd(
                    serverBiddingStartTs = 28,
                    serverBiddingFinishTs = 29,
                    adUnits = listOf(
                        AdUnit(
                            demandId = "vungle",
                            label = "vungle_bidding_android_inter",
                            pricefloor = 1.24,
                            bidType = BidType.RTB,
                            uid = "1687107176709095424",
                            timeout = 5000,
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString()
                        )
                    ),
                    results = listOf(
                        DemandResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("vungle")
                                every { it.getStats() } returns BidStat(
                                    auctionId = "auction_id_123",
                                    demandId = DemandId("vungle"),
                                    demandStatus = DemandStatus.Successful,
                                    ecpm = 1.24,
                                    auctionPricefloor = 0.1,
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    dsp = "vungle",
                                    adUnit = AdUnit(
                                        demandId = "vungle",
                                        label = "vungle_bidding_android_inter",
                                        pricefloor = 1.24,
                                        bidType = BidType.RTB,
                                        uid = "1687107176709095424",
                                        timeout = 5000,
                                        ext = jsonObject {
                                            "payload" hasValue "payload123"
                                        }.toString()
                                    ),
                                )
                            },
                            demandStatus = DemandStatus.Successful
                        ),
                        DemandResult.UnknownAdapter(
                            adUnit = getUnknowAdapterAdUnit(demandId = "demId7")
                        ),
                    )
                ),
                demandResults = listOf(
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("bidmachine"),
                                demandStatus = DemandStatus.Successful,
                                ecpm = 0.20,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = "liftoff",
                                adUnit = AdUnit(
                                    demandId = "bidmachine",
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    bidType = BidType.CPM,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("bidmachine")
                        },
                        demandStatus = DemandStatus.Successful,
                    ),
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("admob"),
                                demandStatus = DemandStatus.NoFill,
                                ecpm = 26.0,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = null,
                                adUnit = AdUnit(
                                    demandId = "admob",
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 26.0,
                                    bidType = BidType.CPM,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("admob")
                        },
                        demandStatus = DemandStatus.NoFill,
                    ),
                    DemandResult.UnknownAdapter(
                        adUnit = getUnknowAdapterAdUnit(demandId = "dem4")
                    ),
                    DemandResult.UnknownAdapter(
                        adUnit = getUnknowAdapterAdUnit(demandId = "dem3")
                    ),
                )
            )
        )

        val expected = RoundStat(
            auctionId = "auction_id_123",
            pricefloor = 1.1,
            demands = listOf(
                StatsAdUnit(
                    demandId = "admob",
                    status = DemandStatus.NoFill.code,
                    price = 26.0,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitUid = "123",
                    adUnitLabel = "dem1_label",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "vungle",
                    status = DemandStatus.Win.code,
                    price = 1.24,
                    tokenStartTs = 678L,
                    tokenFinishTs = 679L,
                    bidType = BidType.RTB.code,
                    fillStartTs = 916,
                    fillFinishTs = 917,
                    adUnitLabel = "vungle_bidding_android_inter",
                    adUnitUid = "1687107176709095424",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "bidmachine",
                    status = DemandStatus.Successful.code,
                    price = 0.2,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitLabel = "dem1_label",
                    adUnitUid = "123",
                    ext = JSONObject()
                ),
                getDemandStatAdapter(demandId = "dem3", status = DemandStatus.UnknownAdapter),
                getDemandStatAdapter(demandId = "dem4", status = DemandStatus.UnknownAdapter),
                getDemandStatAdapter(demandId = "bid3", status = DemandStatus.UnknownAdapter),
            ),
            winnerEcpm = 1.24,
            noBids = listOf(
                BidsInfo(
                    bidType = BidType.RTB.code,
                    demandId = "dem5",
                    label = "dem5_label",
                    price = 0.2,
                    uid = "12365",
                    ext = JSONObject()
                ),
                BidsInfo(
                    bidType = BidType.RTB.code,
                    demandId = "dem6",
                    label = "dem6_label",
                    price = 0.02,
                    uid = "12356",
                    ext = JSONObject()
                )
            ),
            winnerDemandId = DemandId("vungle"),
        )

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `it should save results, Bidding winner`() = runTest {
        // create mock data for Bid
        testee.markAuctionStarted(
            auctionId = "auction_id_123",
            adTypeParam = AdTypeParam.Interstitial(
                activity = mockk(),
                pricefloor = 1.1,
                auctionKey = null
            )
        )
        val actual = testee.addRoundResults(
            AuctionResult.Results(
                serverBiddingResult = ServerBiddingResult.FilledAd(
                    serverBiddingStartTs = 28,
                    serverBiddingFinishTs = 29,
                    adUnits = listOf(
                        AdUnit(
                            demandId = "bidmachine",
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString(),
                            label = "bidmachine_label",
                            pricefloor = 1.1,
                            bidType = BidType.RTB,
                            timeout = 5000,
                            uid = "1234"
                        )
                    ),
                    results = listOf(
                        DemandResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("bidmachine")
                                every { it.getStats() } returns BidStat(
                                    auctionId = "auction_id_123",
                                    demandId = DemandId("bidmachine"),
                                    demandStatus = DemandStatus.Successful,
                                    ecpm = 1.5,
                                    auctionPricefloor = 0.11,
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    dsp = "liftoff",
                                    adUnit = AdUnit(
                                        bidType = BidType.RTB,
                                        demandId = "bidmachine",
                                        ext = null,
                                        label = "bidmachine_label",
                                        pricefloor = 1.5,
                                        timeout = 5000,
                                        uid = "1234"
                                    ),
                                )
                            },
                            demandStatus = DemandStatus.Successful
                        ),
                    )
                ),
                demandResults = listOf(
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("dem1"),
                                demandStatus = DemandStatus.Successful,
                                ecpm = 1.3,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = "liftoff",
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem1",
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("dem1")
                        },
                        demandStatus = DemandStatus.Successful,
                    ),
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("dem2"),
                                demandStatus = DemandStatus.NoFill,
                                ecpm = 10.5,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = "liftoff",
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem2",
                                    ext = null,
                                    label = "dem2_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("dem2")
                        },
                        demandStatus = DemandStatus.NoFill,
                    ),
                    DemandResult.UnknownAdapter(
                        adUnit = getUnknowAdapterAdUnit(demandId = "dem3")
                    ),
                    DemandResult.UnknownAdapter(
                        adUnit = getUnknowAdapterAdUnit(demandId = "dem4")
                    )
                )
            )
        )
        val expected = RoundStat(
            auctionId = "auction_id_123",
            pricefloor = 1.1,
            demands = listOf(
                StatsAdUnit(
                    demandId = "dem2",
                    status = DemandStatus.NoFill.code,
                    price = 10.5,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitUid = "123",
                    adUnitLabel = "dem2_label",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "bidmachine",
                    status = DemandStatus.Win.code,
                    price = 1.5,
                    fillStartTs = 916,
                    fillFinishTs = 917,
                    tokenStartTs = 678L,
                    tokenFinishTs = 679L,
                    bidType = BidType.RTB.code,
                    adUnitUid = "1234",
                    adUnitLabel = "bidmachine_label",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "dem1",
                    status = DemandStatus.Successful.code,
                    price = 1.3,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitUid = "123",
                    adUnitLabel = "dem1_label",
                    ext = JSONObject()
                ),
                getDemandStatAdapter(demandId = "dem3", status = DemandStatus.UnknownAdapter),
                getDemandStatAdapter(demandId = "dem4", status = DemandStatus.UnknownAdapter),
            ),
            winnerEcpm = 1.5,
            winnerDemandId = DemandId("bidmachine"),
            noBids = listOf(
                BidsInfo(
                    bidType = BidType.RTB.code,
                    demandId = "dem5",
                    label = "dem5_label",
                    price = 0.2,
                    uid = "12365",
                    ext = JSONObject()
                ),
                BidsInfo(
                    bidType = BidType.RTB.code,
                    demandId = "dem6",
                    label = "dem6_label",
                    price = 0.02,
                    uid = "12356",
                    ext = JSONObject()
                )
            )
        )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `it should send stat, Bidding wins`() = runTest {
        val systemTime = freezeTime(100500L)
        val noBids = listOf(
            AdUnit(
                bidType = BidType.RTB,
                demandId = "dem5",
                label = "dem5_label",
                pricefloor = 0.2,
                timeout = 5000L,
                uid = "12365",
                ext = ""
            ),
            AdUnit(
                bidType = BidType.RTB,
                demandId = "dem6",
                label = "dem6_label",
                pricefloor = 0.02,
                uid = "12356",
                timeout = 5000L,
                ext = ""
            )
        )
        val auctionData = AuctionResponse(
            adUnits = listOf(),
            pricefloor = 0.01,
            auctionId = "auction_id_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = true,
            noBids = noBids,
            auctionTimeout = 1000L
        )
        testee.markAuctionStarted(
            auctionId = "auction_id_123",
            adTypeParam = AdTypeParam.Interstitial(
                activity = mockk(),
                pricefloor = 1.1,
                auctionKey = null,
            )
        )
        val roundStat = testee.addRoundResults(
            AuctionResult.Results(
                serverBiddingResult = ServerBiddingResult.FilledAd(
                    serverBiddingStartTs = 28,
                    serverBiddingFinishTs = 29,
                    adUnits = listOf(
                        AdUnit(
                            demandId = "bidmachine",
                            label = "bidmachine_label",
                            pricefloor = 0.0,
                            bidType = BidType.RTB,
                            uid = "1234",
                            timeout = 5000,
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString()
                        ),
                        AdUnit(
                            demandId = "meta",
                            label = "meta_label",
                            pricefloor = 0.0,
                            bidType = BidType.RTB,
                            uid = "1232",
                            timeout = 5000,
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString()
                        )

                    ),
                    results = listOf(
                        DemandResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("bidmachine")
                                every { it.getStats() } returns BidStat(
                                    auctionId = "auction_id_123",
                                    demandId = DemandId("bidmachine"),
                                    demandStatus = DemandStatus.Successful,
                                    ecpm = 1.5,
                                    auctionPricefloor = 0.11,
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    dsp = "liftoff",
                                    adUnit = AdUnit(
                                        demandId = "bidmachine",
                                        bidType = BidType.RTB,
                                        ext = null,
                                        label = "bidmachine_label",
                                        pricefloor = 0.1,
                                        timeout = 5000,
                                        uid = "1234"
                                    ),
                                )
                            },
                            demandStatus = DemandStatus.Successful
                        ),
                    )
                ),
                demandResults = listOf(
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("dem1"),
                                demandStatus = DemandStatus.Successful,
                                ecpm = 1.3,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = "liftoff",
                                adUnit = AdUnit(
                                    demandId = "dem1",
                                    bidType = BidType.CPM,
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("dem1")
                        },
                        demandStatus = DemandStatus.Successful,
                    ),
                    DemandResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                auctionId = "auction_id_123",
                                demandId = DemandId("dem2"),
                                demandStatus = DemandStatus.NoFill,
                                ecpm = 10.5,
                                auctionPricefloor = 0.21,
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                dsp = "liftoff",
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem2",
                                    ext = null,
                                    label = "dem2_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                            )
                            every { it.demandId } returns DemandId("dem2")
                        },
                        demandStatus = DemandStatus.NoFill,
                    )
                )
            )
        )

        val actual = testee.sendAuctionStats(
            auctionData = auctionData,
            roundStat = roundStat,
            demandAd = DemandAd(AdType.Interstitial)
        )
        val expected = StatsRequestBody(
            auctionId = "auction_id_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            auctionPricefloor = 0.01,
            adUnits = listOf(
                StatsAdUnit(
                    demandId = "dem2",
                    status = DemandStatus.NoFill.code,
                    price = 10.5,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitUid = "123",
                    adUnitLabel = "dem2_label",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "bidmachine",
                    status = DemandStatus.Win.code,
                    price = 1.5,
                    tokenStartTs = 678,
                    tokenFinishTs = 679,
                    bidType = BidType.RTB.code,
                    fillStartTs = 916,
                    fillFinishTs = 917,
                    adUnitUid = "1234",
                    adUnitLabel = "bidmachine_label",
                    ext = JSONObject()
                ),
                StatsAdUnit(
                    demandId = "dem1",
                    status = "LOSE",
                    price = 1.3,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = 986,
                    fillFinishTs = 987,
                    adUnitUid = "123",
                    adUnitLabel = "dem1_label",
                    ext = JSONObject()
                )
            ),
            result = StatsResult(
                status = "SUCCESS",
                winnerDemandId = "bidmachine",
                bidType = BidType.RTB.code,
                price = 1.5,
                winnerAdUnitUid = "1234",
                winnerAdUnitLabel = "bidmachine_label",
                auctionStartTs = systemTime,
                auctionFinishTs = systemTime,
                banner = null,
                interstitial = InterstitialRequest,
                rewarded = null,
            ),
        )
        assertThat(actual).isEqualTo(expected)
    }

    private fun getDemandStatAdapter(demandId: String, status: DemandStatus) = StatsAdUnit(
        demandId = demandId,
        status = status.code,
        price = null,
        bidType = null,
        tokenStartTs = null,
        tokenFinishTs = null,
        fillStartTs = null,
        fillFinishTs = null,
        adUnitUid = null,
        adUnitLabel = null,
        ext = JSONObject()
    )

    private fun getUnknowAdapterAdUnit(demandId: String) =
        AdUnit(
            bidType = BidType.RTB,
            demandId = demandId,
            label = "dem7_label",
            pricefloor = 0.021,
            uid = "123567",
            timeout = 5000L,
            ext = ""
        )
}