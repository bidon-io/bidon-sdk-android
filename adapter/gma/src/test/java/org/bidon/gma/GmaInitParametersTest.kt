package org.bidon.gma

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import org.junit.Test

class GmaInitParametersTest {

    private val activity = mockk<Activity>()

    @Test
    fun `GmaInitParameters holds appId`() {
        val params = GmaInitParameters(appId = "ca-app-pub-xyz")
        assertThat(params.appId).isEqualTo("ca-app-pub-xyz")
    }

    @Test
    fun `GmaBannerAuctionParams_Network extracts adUnitId from adUnit extra`() {
        val adUnit = AdUnit(
            demandId = "gma",
            pricefloor = 1.5,
            label = "banner_label",
            bidType = BidType.CPM,
            ext = jsonObject {
                "ad_unit_id" hasValue "banner_unit_123"
            }.toString(),
            uid = "uid1",
            timeout = 5000,
        )
        val params = GmaBannerAuctionParams.Network(
            activity = activity,
            bannerFormat = BannerFormat.Banner,
            containerWidth = 320f,
            adUnit = adUnit,
        )
        assertThat(params.adUnitId).isEqualTo("banner_unit_123")
        assertThat(params.price).isEqualTo(1.5)
        assertThat(params.bannerFormat).isEqualTo(BannerFormat.Banner)
    }

    @Test
    fun `GmaBannerAuctionParams_Network returns null adUnitId when missing`() {
        val adUnit = AdUnit(
            demandId = "gma",
            pricefloor = 1.0,
            label = "label",
            bidType = BidType.CPM,
            ext = jsonObject {}.toString(),
            uid = "uid2",
            timeout = 5000,
        )
        val params = GmaBannerAuctionParams.Network(
            activity = activity,
            bannerFormat = BannerFormat.MRec,
            containerWidth = 300f,
            adUnit = adUnit,
        )
        assertThat(params.adUnitId).isNull()
    }

    @Test
    fun `GmaFullscreenAdAuctionParams_Network extracts adUnitId from adUnit extra`() {
        val adUnit = AdUnit(
            demandId = "gma",
            pricefloor = 2.5,
            label = "interstitial_label",
            bidType = BidType.CPM,
            ext = jsonObject {
                "ad_unit_id" hasValue "interstitial_unit_456"
            }.toString(),
            uid = "uid3",
            timeout = 5000,
        )
        val params = GmaFullscreenAdAuctionParams.Network(
            activity = activity,
            adUnit = adUnit,
        )
        assertThat(params.adUnitId).isEqualTo("interstitial_unit_456")
        assertThat(params.price).isEqualTo(2.5)
    }

    @Test
    fun `GmaFullscreenAdAuctionParams_Network price equals adUnit pricefloor`() {
        val adUnit = AdUnit(
            demandId = "gma",
            pricefloor = 3.75,
            label = "rewarded_label",
            bidType = BidType.CPM,
            ext = jsonObject {
                "ad_unit_id" hasValue "rewarded_unit_789"
            }.toString(),
            uid = "uid4",
            timeout = 5000,
        )
        val params = GmaFullscreenAdAuctionParams.Network(
            activity = activity,
            adUnit = adUnit,
        )
        assertThat(params.price).isEqualTo(3.75)
    }
}
