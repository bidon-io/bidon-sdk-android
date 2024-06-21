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
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.impl.MaxEcpmAuctionResolver
import org.bidon.sdk.auction.models.*
import org.bidon.sdk.auction.usecases.impl.AuctionStatImpl
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.*
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.di.SimpleDiStorage
import org.bidon.sdk.utils.json.jsonObject
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
            RoundResult.Results(
                biddingResult = BiddingResult.FilledAd(
                    serverBiddingStartTs = 28,
                    serverBiddingFinishTs = 29,
                    adUnits = listOf(
                        AdUnit(
                            demandId = "bidmachine",
                            label = "bidmachine_label",
                            pricefloor = 0.0,
                            bidType = BidType.CPM,
                            uid = "123",
                            timeout = 5000,
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString()
                        ),
                        AdUnit(
                            demandId = "meta",
                            label = "meta_label",
                            pricefloor = 0.0,
                            bidType = BidType.CPM,
                            uid = "123",
                            timeout = 5000,
                            ext = jsonObject {
                                "payload" hasValue "payload123"
                            }.toString()
                        )
                    ),
                    results = listOf(
                        AuctionResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("bidmachine")
                                every { it.getStats() } returns BidStat(
                                    demandId = DemandId("bidmachine"),
                                    ecpm = 1.2,
                                    auctionId = "auction_id_123",
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    roundStatus = RoundStatus.Successful,
                                    dspSource = "liftoff",
                                    auctionPricefloor = 0.1,
                                    adUnit = AdUnit(
                                        demandId = "bidmachine",
                                        ext = null,
                                        label = "bidmachine_label",
                                        pricefloor = 10.0,
                                        timeout = 5000,
                                        bidType = BidType.RTB,
                                        uid = "123",
                                    ),
                                    tokenInfo = TokenInfo(
                                        token = "token123",
                                        tokenStartTs = 678L,
                                        tokenFinishTs = 679L,
                                        status = TokenInfo.Status.SUCCESS.code,
                                    ),
                                )
                            },
                            roundStatus = RoundStatus.Successful
                        ),
                        AuctionResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("meta")
                                every { it.getStats() } returns BidStat(
                                    demandId = DemandId("meta"),
                                    adUnit = AdUnit(
                                        demandId = "meta",
                                        label = "meta_label",
                                        pricefloor = 1.0,
                                        bidType = BidType.RTB,
                                        uid = "123",
                                        timeout = 5000,
                                        ext = jsonObject {
                                            "payload" hasValue "payload123"
                                        }.toString()
                                    ),
                                    ecpm = 1.15,
                                    auctionId = "auction_id_123",
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    roundStatus = RoundStatus.Successful,
                                    dspSource = "liftoff",
                                    auctionPricefloor = 0.1,
                                    tokenInfo = TokenInfo(
                                        token = "token123",
                                        tokenStartTs = 678L,
                                        tokenFinishTs = 679L,
                                        status = TokenInfo.Status.SUCCESS.code,
                                    ),
                                )
                            },
                            roundStatus = RoundStatus.Successful
                        ),
                        AuctionResult.UnknownAdapter(
                            adapterName = "bid3",
                            type = AuctionResult.UnknownAdapter.Type.Bidding
                        ),
                    )
                ),
                networkResults = listOf(
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem1"),
                                ecpm = 1.3,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.Successful,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    demandId = "dem1",
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    bidType = BidType.CPM,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem1")
                        },
                        roundStatus = RoundStatus.Successful,
                    ),
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem2"),
                                ecpm = 1.5,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.NoFill,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    demandId = "dem2",
                                    ext = null,
                                    label = "dem2_label",
                                    pricefloor = 0.3,
                                    bidType = BidType.CPM,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem2")
                        },
                        roundStatus = RoundStatus.NoFill,
                    ),
                    AuctionResult.UnknownAdapter(
                        adapterName = "dem3",
                        type = AuctionResult.UnknownAdapter.Type.Network
                    ),
                    AuctionResult.UnknownAdapter(
                        adapterName = "dem4",
                        type = AuctionResult.UnknownAdapter.Type.Network
                    ),
                ),
                pricefloor = 1.1
            )
        )

        assertThat(actual).isEqualTo(
            RoundStat(
                auctionId = "auction_id_123",
                pricefloor = 1.1,
                demands = listOf(
                    StatsAdUnit(
                        demandId = "dem1",
                        status = RoundStatus.Successful.code,
                        price = 1.3,
                        tokenStartTs = 678L,
                        tokenFinishTs = 679L,
                        bidType = BidType.CPM.code,
                        fillStartTs = 986,
                        fillFinishTs = 987,
                        adUnitUid = "123",
                        adUnitLabel = "dem1_label",
                    ),
                    StatsAdUnit(
                        demandId = "dem2",
                        status = RoundStatus.NoFill.code,
                        price = 1.5,
                        tokenStartTs = 678L,
                        tokenFinishTs = 679L,
                        bidType = BidType.CPM.code,
                        fillStartTs = 986,
                        fillFinishTs = 987,
                        adUnitLabel = "dem2_label",
                        adUnitUid = "123",
                    ),
                    getDemandStatAdapter(demandId = "dem3", status = RoundStatus.UnknownAdapter),
                    getDemandStatAdapter(demandId = "dem4", status = RoundStatus.UnknownAdapter),
                ),
                winnerEcpm = 1.3,
                winnerDemandId = DemandId("dem1"),
            )
        )
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
            RoundResult.Results(
                biddingResult = BiddingResult.FilledAd(
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
                        AuctionResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("bidmachine")
                                every { it.getStats() } returns BidStat(
                                    demandId = DemandId("bidmachine"),
                                    ecpm = 1.5,
                                    auctionId = "auction_id_123",
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    roundStatus = RoundStatus.Successful,
                                    dspSource = "liftoff",
                                    auctionPricefloor = 0.11,
                                    adUnit = AdUnit(
                                        bidType = BidType.RTB,
                                        demandId = "bidmachine",
                                        ext = null,
                                        label = "bidmachine_label",
                                        pricefloor = 0.0,
                                        timeout = 5000,
                                        uid = "1234"
                                    ),
                                    tokenInfo = TokenInfo(
                                        token = "token123",
                                        tokenStartTs = 678L,
                                        tokenFinishTs = 679L,
                                        status = TokenInfo.Status.SUCCESS.code,
                                    ),
                                )
                            },
                            roundStatus = RoundStatus.Successful
                        ),
                    )
                ),
                networkResults = listOf(
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem1"),
                                ecpm = 1.3,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.Successful,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem1",
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem1")
                        },
                        roundStatus = RoundStatus.Successful,
                    ),
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem2"),
                                ecpm = 10.5,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.NoFill,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem2",
                                    ext = null,
                                    label = "dem2_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem2")
                        },
                        roundStatus = RoundStatus.NoFill,
                    ),
                    AuctionResult.UnknownAdapter(
                        adapterName = "dem3",
                        type = AuctionResult.UnknownAdapter.Type.Network
                    ),
                    AuctionResult.UnknownAdapter(
                        adapterName = "dem4",
                        type = AuctionResult.UnknownAdapter.Type.Network
                    )
                ),
                pricefloor = 1.1
            )
        )

        assertThat(actual).isEqualTo(
            RoundStat(
                auctionId = "auction_id_123",
                pricefloor = 1.1,
                demands = listOf(
                    StatsAdUnit(
                        demandId = "dem1",
                        status = RoundStatus.Successful.code,
                        price = 1.3,
                        tokenStartTs = 678L,
                        tokenFinishTs = 679L,
                        bidType = BidType.CPM.code,
                        fillStartTs = 986,
                        fillFinishTs = 987,
                        adUnitUid = "123",
                        adUnitLabel = "dem1_label",
                    ),
                    StatsAdUnit(
                        demandId = "dem2",
                        status = RoundStatus.NoFill.code,
                        price = 10.5,
                        tokenStartTs = 678L,
                        tokenFinishTs = 679L,
                        bidType = BidType.CPM.code,
                        fillStartTs = 986,
                        fillFinishTs = 987,
                        adUnitUid = "123",
                        adUnitLabel = "dem2_label",
                    ),
                    getDemandStatAdapter(demandId = "dem3", status = RoundStatus.UnknownAdapter),
                    getDemandStatAdapter(demandId = "dem4", status = RoundStatus.UnknownAdapter),
                ),
                winnerEcpm = 1.5,
                winnerDemandId = DemandId("bidmachine"),
            )
        )
    }

    @Test
    fun `it should send stat, Bidding wins`() = runTest {
        val systemTime = freezeTime(100500L)
        val auctionData = AuctionResponse(
            adUnits = listOf(),
            pricefloor = 0.01,
            auctionId = "auction_id_123",
            auctionConfigurationId = 10,
            auctionConfigurationUid = "10",
            externalWinNotificationsEnabled = true,
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
        testee.addRoundResults(
            RoundResult.Results(
                biddingResult = BiddingResult.FilledAd(
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
                        AuctionResult.Bidding(
                            adSource = mockk<AdSource<*>>(relaxed = true).also {
                                every { it.demandId } returns DemandId("bidmachine")
                                every { it.getStats() } returns BidStat(
                                    demandId = DemandId("bidmachine"),
                                    ecpm = 1.5,
                                    auctionId = "auction_id_123",
                                    fillStartTs = 916,
                                    fillFinishTs = 917,
                                    roundStatus = RoundStatus.Successful,
                                    dspSource = "liftoff",
                                    auctionPricefloor = 0.11,
                                    adUnit = AdUnit(
                                        demandId = "bidmachine",
                                        bidType = BidType.RTB,
                                        ext = null,
                                        label = "bidmachine_label",
                                        pricefloor = 0.1,
                                        timeout = 5000,
                                        uid = "1234"
                                    ),
                                    tokenInfo = TokenInfo(
                                        token = "token123",
                                        tokenStartTs = 678L,
                                        tokenFinishTs = 679L,
                                        status = TokenInfo.Status.SUCCESS.code,
                                    ),
                                )
                            },
                            roundStatus = RoundStatus.Successful
                        ),
                    )
                ),
                networkResults = listOf(
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem1"),
                                ecpm = 1.3,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.Successful,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    demandId = "dem1",
                                    bidType = BidType.CPM,
                                    ext = null,
                                    label = "dem1_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem1")
                        },
                        roundStatus = RoundStatus.Successful,
                    ),
                    AuctionResult.Network(
                        adSource = mockk<AdSource<*>>(relaxed = true).also {
                            every { it.getStats() } returns BidStat(
                                demandId = DemandId("dem2"),
                                ecpm = 10.5,
                                auctionId = "auction_id_123",
                                fillStartTs = 986,
                                fillFinishTs = 987,
                                roundStatus = RoundStatus.NoFill,
                                dspSource = "liftoff",
                                auctionPricefloor = 0.21,
                                adUnit = AdUnit(
                                    bidType = BidType.CPM,
                                    demandId = "dem2",
                                    ext = null,
                                    label = "dem2_label",
                                    pricefloor = 0.3,
                                    timeout = 5000,
                                    uid = "123"
                                ),
                                tokenInfo = TokenInfo(
                                    token = "token123",
                                    tokenStartTs = 678L,
                                    tokenFinishTs = 679L,
                                    status = TokenInfo.Status.SUCCESS.code,
                                ),
                            )
                            every { it.demandId } returns DemandId("dem2")
                        },
                        roundStatus = RoundStatus.NoFill,
                    )
                ),
                pricefloor = 1.1
            )
        )

        val actual = testee.sendAuctionStats(
            auctionData = auctionData,
            demandAd = DemandAd(AdType.Interstitial)
        )
        assertThat(actual).isEqualTo(
            StatsRequestBody(
                auctionId = "auction_id_123",
                auctionConfigurationId = 10,
                auctionConfigurationUid = "10",
                auctionPricefloor = 1.1,
                result = ResultBody(
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
                adUnits = listOf()
            )
        )
    }

    private fun getDemandStatAdapter(demandId: String, status: RoundStatus) = StatsAdUnit(
        demandId = demandId,
        status = status.code,
        price = null,
        bidType = BidType.CPM.code,
        tokenStartTs = null,
        tokenFinishTs = null,
        fillStartTs = null,
        fillFinishTs = null,
        adUnitUid = null,
        adUnitLabel = null,
    )

    private fun getBiddingStatAdapter(demandId: String, status: RoundStatus) =
        StatsAdUnit(
            demandId = demandId,
            status = status.code,
            bidType = BidType.RTB.code,
            price = null,
            fillStartTs = null,
            fillFinishTs = null,
            adUnitUid = null,
            adUnitLabel = null,
            tokenStartTs = null,
            tokenFinishTs = null,
        )
}