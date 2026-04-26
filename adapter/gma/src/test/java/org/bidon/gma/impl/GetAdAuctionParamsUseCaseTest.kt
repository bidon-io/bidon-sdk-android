package org.bidon.gma.impl

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.gma.GmaBannerAuctionParams
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import kotlin.test.Test

class GetAdAuctionParamsUseCaseTest {

    private val testee by lazy {
        GetAdAuctionParamsUseCase()
    }

    private val activity = mockk<Activity>()

    @Test
    fun `parse banner AdUnit CPM`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 2.6,
                adUnit = AdUnit(
                    demandId = "gma",
                    pricefloor = 2.6,
                    label = "label123",
                    bidType = BidType.CPM,
                    ext = jsonObject {
                        "ad_unit_id" hasValue "gma_banner_unit_id"
                    }.toString(),
                    uid = "uid123",
                    timeout = 5000
                ),
                optBannerFormat = BannerFormat.MRec,
                optContainerWidth = 140f,
            )
        }
        val actual = testee.invoke(
            auctionParamsScope = auctionParamsScope,
            adType = AdType.Banner,
        ).getOrThrow()

        require(actual is GmaBannerAuctionParams.Network)
        assertThat(actual.price).isEqualTo(2.6)
        assertThat(actual.adUnitId).isEqualTo("gma_banner_unit_id")
        assertThat(actual.bannerFormat).isEqualTo(BannerFormat.MRec)
    }

    @Test
    fun `parse banner AdUnit returns GmaBannerAuctionParams_Network`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 3.5,
                adUnit = AdUnit(
                    demandId = "gma",
                    pricefloor = 3.5,
                    label = "label888",
                    bidType = BidType.CPM,
                    ext = jsonObject {
                        "ad_unit_id" hasValue "gma_banner_unit_888"
                    }.toString(),
                    uid = "uid123",
                    timeout = 5000
                ),
                optBannerFormat = BannerFormat.Banner,
                optContainerWidth = 320f,
            )
        }
        val actual = testee.invoke(
            auctionParamsScope = auctionParamsScope,
            adType = AdType.Banner,
        ).getOrThrow()

        require(actual is GmaBannerAuctionParams.Network)
        assertThat(actual.price).isEqualTo(3.5)
        assertThat(actual.adUnitId).isEqualTo("gma_banner_unit_888")
    }

    @Test
    fun `parse interstitial AdUnit returns GmaFullscreenAdAuctionParams_Network`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 2.75,
                adUnit = AdUnit(
                    demandId = "gma",
                    pricefloor = 2.75,
                    label = "label_inter",
                    bidType = BidType.CPM,
                    ext = jsonObject {
                        "ad_unit_id" hasValue "gma_inter_unit_id"
                    }.toString(),
                    uid = "uid123",
                    timeout = 5000
                ),
                optBannerFormat = null,
                optContainerWidth = null,
            )
        }
        val actual = testee.invoke(
            auctionParamsScope = auctionParamsScope,
            adType = AdType.Interstitial,
        ).getOrThrow()

        require(actual is GmaFullscreenAdAuctionParams.Network)
        assertThat(actual.price).isEqualTo(2.75)
        assertThat(actual.adUnitId).isEqualTo("gma_inter_unit_id")
    }

    @Test
    fun `parse rewarded AdUnit returns GmaFullscreenAdAuctionParams_Network`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 4.0,
                adUnit = AdUnit(
                    demandId = "gma",
                    pricefloor = 4.0,
                    label = "label_rewarded",
                    bidType = BidType.CPM,
                    ext = jsonObject {
                        "ad_unit_id" hasValue "gma_rewarded_unit_id"
                    }.toString(),
                    uid = "uid456",
                    timeout = 5000
                ),
                optBannerFormat = null,
                optContainerWidth = null,
            )
        }
        val actual = testee.invoke(
            auctionParamsScope = auctionParamsScope,
            adType = AdType.Rewarded,
        ).getOrThrow()

        require(actual is GmaFullscreenAdAuctionParams.Network)
        assertThat(actual.price).isEqualTo(4.0)
        assertThat(actual.adUnitId).isEqualTo("gma_rewarded_unit_id")
    }
}
