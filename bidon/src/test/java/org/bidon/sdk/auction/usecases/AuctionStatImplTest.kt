package org.bidon.sdk.auction.usecases

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.helper.DeviceType
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.config.models.auctions.impl.Admob
import org.bidon.sdk.config.models.auctions.impl.Applovin
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.DemandStat
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.di.get
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 27/06/2023.
 */
internal class AuctionStatImplTest : ConcurrentTest() {

    private val statRequest: StatsRequestUseCase = mockk(relaxed = true)

    private val testee: AuctionStat by lazy {
        AuctionStatImpl(statRequest)
    }

    @Before
    fun before() {
        mockkObject(DeviceType)
        every { DeviceType.init(any()) } returns Unit
        try {
            get<Context>()
        } catch (e: Exception) {
            DI.init(mockk(relaxed = true))
//        DI.setFactories()
            mockkLog()
        }
    }

    @After
    fun after() {
//        unmockkAll()
    }

    @Test
    fun `it should send AUCTION_CANCELLED state`() = runTest {
        val roundStat = slot<List<RoundStat>>()
        val auctionConfig = AuctionResponse(
            auctionConfigurationId = 10,
            auctionId = "auctionId_123",
            rounds = listOf(
                Round(
                    id = "round1",
                    timeoutMs = 1000L,
                    biddingIds = listOf("bi1", "bi2"),
                    demandIds = listOf("dem1", "dem2"),
                ),
                Round(
                    id = "round2",
                    timeoutMs = 25,
                    demandIds = listOf("dem3", "dem4"),
                    biddingIds = listOf("bi3"),
                ),
            ),
            lineItems = listOf(
                LineItem(
                    demandId = Applovin,
                    pricefloor = 0.25,
                    adUnitId = "AAAA2"
                ),
                LineItem(
                    demandId = Admob,
                    pricefloor = 1.2235,
                    adUnitId = "admob1"
                ),
                LineItem(
                    demandId = Admob,
                    pricefloor = 2.2235,
                    adUnitId = "admob2"
                ),
            ),
            pricefloor = 0.01,
            token = null,
            externalWinNotificationsEnabled = true
        )
        testee.markAuctionStarted(auctionId = "auctionId_123")
        testee.addRoundResults(
            round = auctionConfig.rounds?.first()!!,
            pricefloor = 1.4,
            roundResults = listOf(
                AuctionResult.Network.UnknownAdapter("dem1"),
                AuctionResult.Network.UnknownAdapter("dem2"),
                AuctionResult.Bidding.Failure.TimeoutReached,
            )
        )
        testee.markAuctionCanceled()
        testee.sendAuctionStats(
            auctionData = auctionConfig,
            demandAd = DemandAd(AdType.Interstitial)
        )
        coVerify(exactly = 1) {
            statRequest.invoke(
                auctionId = any(),
                demandAd = any(),
                auctionStartTs = any(),
                auctionFinishTs = any(),
                auctionConfigurationId = any(),
                results = capture(roundStat)
            )
        }
        val actual = roundStat.captured
        assertThat(actual).isEqualTo(
            listOf(
                RoundStat(
                    auctionId = "auctionId_123",
                    roundId = "round1",
                    pricefloor = 1.4,
                    winnerDemandId = null,
                    winnerEcpm = null,
                    demands = listOf(
                        asDemandStatNetwork("dem1", RoundStatus.UnknownAdapter),
                        asDemandStatNetwork("dem2", RoundStatus.UnknownAdapter)
                    ),
                    bidding = asDemandStatBidding(RoundStatus.BidTimeoutReached)
                ),
                RoundStat(
                    auctionId = "auctionId_123",
                    roundId = "round2",
                    pricefloor = null,
                    winnerDemandId = null,
                    winnerEcpm = null,
                    demands = listOf(
                        asDemandStatNetwork("dem3", RoundStatus.AuctionCancelled),
                        asDemandStatNetwork("dem4", RoundStatus.AuctionCancelled)
                    ),
                    bidding = asDemandStatBidding(RoundStatus.AuctionCancelled)
                )
            )
        )
    }

    @Test
    fun `it should send WIN state`() {
        val roundStat = slot<List<RoundStat>>()
        val auctionConfig = AuctionResponse(
            auctionConfigurationId = 10,
            auctionId = "auctionId_123",
            rounds = listOf(
                Round(
                    id = "round1",
                    timeoutMs = 1000L,
                    biddingIds = listOf("bi1", "bi2"),
                    demandIds = listOf("dem1", "dem2"),
                ),
                Round(
                    id = "round2",
                    timeoutMs = 25,
                    demandIds = listOf("dem3", "dem4"),
                    biddingIds = listOf("bi3"),
                ),
            ),
            lineItems = listOf(
                LineItem(
                    demandId = Applovin,
                    pricefloor = 0.25,
                    adUnitId = "AAAA2"
                ),
                LineItem(
                    demandId = Admob,
                    pricefloor = 1.2235,
                    adUnitId = "admob1"
                ),
                LineItem(
                    demandId = Admob,
                    pricefloor = 2.2235,
                    adUnitId = "admob2"
                ),
            ),
            pricefloor = 0.01,
            token = null,
            externalWinNotificationsEnabled = true
        )
        testee.markAuctionStarted(auctionId = "auctionId_123")
        testee.addRoundResults(
            round = auctionConfig.rounds?.first()!!,
            pricefloor = 1.4,
            roundResults = listOf(
                AuctionResult.Network.UnknownAdapter("dem1"),
                AuctionResult.Network.UnknownAdapter("dem2"),
                AuctionResult.Bidding.Failure.TimeoutReached,
            )
        )
        testee.addRoundResults(
            round = auctionConfig.rounds[1],
            pricefloor = 1.5,
            roundResults = listOf(
                AuctionResult.Network.UnknownAdapter("dem3"),
                AuctionResult.Network.UnknownAdapter("dem4"),
                AuctionResult.Bidding.Success(
                    adSource = mockk(relaxed = true) {
                        val a = this
                        every { a.demandId } returns DemandId("bi3")
                        every { a.ad } returns Ad(
                            demandAd = DemandAd(AdType.Interstitial),
                            networkName = "admob",
                            ecpm = 1.5,
                            adUnitId = null,
                            roundId = "r123",
                            currencyCode = AdValue.USD,
                            demandAdObject = mockk(relaxed = true),
                            dsp = null,
                            auctionId = "a123"
                        )
                    },
                    roundStatus = RoundStatus.Successful
                ),
            )
        )
        testee.sendAuctionStats(
            auctionData = auctionConfig,
            demandAd = DemandAd(AdType.Interstitial)
        )
        coVerify(exactly = 1) {
            statRequest.invoke(
                auctionId = any(),
                demandAd = any(),
                auctionStartTs = any(),
                auctionFinishTs = any(),
                auctionConfigurationId = any(),
                results = capture(roundStat)
            )
        }
        val actual = roundStat.captured
        val expect = listOf(
            RoundStat(
                auctionId = "auctionId_123",
                roundId = "round1",
                pricefloor = 1.4,
                winnerDemandId = null,
                winnerEcpm = null,
                demands = listOf(
                    asDemandStatNetwork("dem1", RoundStatus.UnknownAdapter),
                    asDemandStatNetwork("dem2", RoundStatus.UnknownAdapter)
                ),
                bidding = asDemandStatBidding(RoundStatus.BidTimeoutReached)
            ),
            RoundStat(
                auctionId = "auctionId_123",
                roundId = "round2",
                pricefloor = 1.5,
                winnerDemandId = DemandId("bi3"),
                winnerEcpm = 1.5,
                demands = listOf(
                    asDemandStatNetwork("dem3", RoundStatus.UnknownAdapter),
                    asDemandStatNetwork("dem4", RoundStatus.UnknownAdapter)
                ),
                bidding = DemandStat.Bidding(
                    roundStatus = RoundStatus.Win,
                    demandId = DemandId("bi3"),
                    bidStartTs = 0,
                    bidFinishTs = 0,
                    fillStartTs = 0,
                    fillFinishTs = 0,
                    ecpm = 1.5,
                )
            )
        )
        assertThat(actual).isEqualTo(expect)
    }

    private fun asDemandStatNetwork(demandId: String, roundStatus: RoundStatus) = DemandStat.Network(
        roundStatus = roundStatus,
        demandId = DemandId(demandId),
        bidStartTs = null,
        bidFinishTs = null,
        fillStartTs = null,
        fillFinishTs = null,
        adUnitId = null,
        ecpm = null,
    )

    private fun asDemandStatBidding(roundStatus: RoundStatus) = DemandStat.Bidding(
        roundStatus = roundStatus,
        demandId = null,
        bidStartTs = null,
        bidFinishTs = null,
        fillStartTs = null,
        fillFinishTs = null,
        ecpm = null,
    )
}