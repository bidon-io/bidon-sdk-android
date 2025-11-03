package org.bidon.sdk.auction.usecases.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RequestAdUnitUseCaseImpl to verify correct bid status assignment
 * based on price vs pricefloor comparison.
 */
internal class RequestAdUnitUseCaseImplTest : ConcurrentTest() {

    private val testee: RequestAdUnitUseCase = RequestAdUnitUseCaseImpl()

    @Before
    fun setup() {
        mockkLog()
    }

    @Test
    fun `CPM bid below pricefloor should return BelowPricefloor status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 0.5
        val adUnit = createAdUnit(bidType = BidType.CPM, pricefloor = 0.5)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Network::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.BelowPricefloor)
    }

    @Test
    fun `RTB bid below pricefloor should return Lose status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 0.5
        val adUnit = createAdUnit(bidType = BidType.RTB, pricefloor = 0.5)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Bidding::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.Lose)
    }

    @Test
    fun `CPM bid above pricefloor should return Successful status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 1.5
        val adUnit = createAdUnit(bidType = BidType.CPM, pricefloor = 1.5)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Network::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.Successful)
    }

    @Test
    fun `RTB bid above pricefloor should return Successful status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 1.5
        val adUnit = createAdUnit(bidType = BidType.RTB, pricefloor = 1.5)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Bidding::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.Successful)
    }

    @Test
    fun `CPM bid equal to pricefloor should return Successful status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 1.0
        val adUnit = createAdUnit(bidType = BidType.CPM, pricefloor = 1.0)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Network::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.Successful)
    }

    @Test
    fun `RTB bid equal to pricefloor should return Successful status`() = runTest {
        // Given
        val priceFloor = 1.0
        val bidPrice = 1.0
        val adUnit = createAdUnit(bidType = BidType.RTB, pricefloor = 1.0)
        val adSource = createMockAdSource(bidPrice = bidPrice)
        val adTypeParam = createAdTypeParam()

        // When
        val result = testee.invoke(
            adSource = adSource,
            adUnit = adUnit,
            adTypeParam = adTypeParam,
            priceFloor = priceFloor
        )

        // Then
        assertThat(result).isInstanceOf(AuctionResult.Bidding::class.java)
        assertThat(result.roundStatus).isEqualTo(RoundStatus.Successful)
    }

    private fun createAdUnit(
        bidType: BidType,
        pricefloor: Double,
        demandId: String = "test_demand",
        timeout: Long = 5000L
    ) = AdUnit(
        demandId = demandId,
        label = "test_label",
        pricefloor = pricefloor,
        uid = "test_uid",
        bidType = bidType,
        timeout = timeout,
        ext = null
    )

    private fun createAdTypeParam() = AdTypeParam.Interstitial(
        activity = mockk(relaxed = true),
        pricefloor = 0.0,
        auctionKey = null
    )

    private fun createMockAdSource(bidPrice: Double): AdSource<AdAuctionParams> {
        val adSource = mockk<AdSource<AdAuctionParams>>(relaxed = true)
        val adEventFlow = MutableSharedFlow<AdEvent>(replay = 1)
        val mockAd = mockk<Ad>(relaxed = true)

        every { mockAd.price } returns bidPrice
        every { adSource.demandId } returns DemandId("test_demand")

        // Mock getAd() as AdSource extends StatisticsCollector
        every { (adSource as org.bidon.sdk.stats.StatisticsCollector).getAd() } returns mockAd

        every { adSource.adEvent } returns adEventFlow

        coEvery { adSource.getAuctionParam(any<AdAuctionParamSource>()) } returns Result.success(
            mockk<AdAuctionParams>(relaxed = true).also { params ->
                every { params.price } returns bidPrice
                every { params.adUnit } returns mockk(relaxed = true)
            }
        )

        // Simulate successful ad fill
        coEvery { adSource.load(any()) } coAnswers {
            adEventFlow.emit(AdEvent.Fill(mockAd))
        }

        return adSource
    }
}
