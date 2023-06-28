package org.bidon.sdk.auction.usecases

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.helper.DeviceType
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.config.models.auctions.impl.Admob
import org.bidon.sdk.config.models.auctions.impl.Applovin
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.networking.BaseResponse
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 27/06/2023.
 */
internal class AuctionStatImplTest : ConcurrentTest() {

    private val statRequest: StatsRequestUseCase = mockk()

    private val testee: AuctionStat by lazy {
        AuctionStatImpl(mockk(relaxed = true))
    }

    @Before
    override fun setUp() {
        super.setUp()
        mockkObject(DeviceType)
        every { DeviceType.init(any()) } returns Unit
        DI.init(mockk(relaxed = true))
//        DI.setFactories()
        mockkLog()
    }

    @After
    fun after() {
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `it should send AUCTION_CANCELLED state`() = runTest {
        val roundStat = slot<List<RoundStat>>()
        coEvery {
            statRequest.invoke(
                auctionId = any(),
                demandAd = any(),
                auctionStartTs = any(),
                auctionFinishTs = any(),
                auctionConfigurationId = any(),
                results = capture(roundStat)
            )
        } answers {
            println(">>" + roundStat.captured)
            Result.success(BaseResponse(success = true, error = null))
        }
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
        advanceUntilIdle()
        advanceUntilIdle()
        advanceUntilIdle()
        coVerify(exactly = 1) {
            statRequest.invoke(
                auctionId = any(),
                demandAd = any(),
                auctionStartTs = any(),
                auctionFinishTs = any(),
                auctionConfigurationId = any(),
                results = any()
            )
        }
    }

    @Test
    fun `it should send WIN state`() {
        TODO()
    }
}