package org.bidon.sdk.auction.impl

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.impl.ExecuteAuctionUseCaseImpl
import org.bidon.sdk.config.models.adapters.Process
import org.bidon.sdk.config.models.adapters.TestAdapter
import org.bidon.sdk.config.models.adapters.TestAdapterParameters
import org.bidon.sdk.config.models.adapters.TestBiddingAdapter
import org.bidon.sdk.config.models.auctions.impl.Admob
import org.bidon.sdk.config.models.auctions.impl.BidMachine
import org.bidon.sdk.config.models.base.ConcurrentTest
import org.bidon.sdk.mockkLog
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.di.DI
import org.bidon.sdk.utils.json.jsonObject
import org.junit.After
import org.junit.Before

/**
 * Created by Aleksei Cherniaev on 26/06/2023.
 */
internal class ExecuteAuctionUseCaseImplTest : ConcurrentTest() {

    private val auctionConfig = AuctionResponse(
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
    )

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
    }
}