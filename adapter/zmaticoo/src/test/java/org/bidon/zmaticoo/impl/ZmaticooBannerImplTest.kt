package org.bidon.zmaticoo.impl

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import org.bidon.zmaticoo.ZmaticooDemandId
import org.junit.Test

class ZmaticooBannerImplTest {
    private val activity = mockk<Activity>()
    private val testee by lazy {
        ZmaticooBannerImpl().apply {
            addDemandId(ZmaticooDemandId)
        }
    }

    @Test
    fun `implements required interfaces`() {
        assertThat(testee).isInstanceOf(AdEventFlow::class.java)
        assertThat(testee).isInstanceOf(StatisticsCollector::class.java)
    }

    @Test
    fun `parse banner AdUnit RTB`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 1.5,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 1.5,
                    label = "label_banner",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "placement_id" hasValue "1004273176"
                        "payload" hasValue "test_payload_banner"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_banner"
                ),
                optBannerFormat = BannerFormat.Banner,
                optContainerWidth = 320f,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is ZmaticooBannerAuctionParams)
        assertThat(actual.adUnit).isEqualTo(
            AdUnit(
                demandId = "zmaticoo",
                pricefloor = 1.5,
                label = "label_banner",
                bidType = BidType.RTB,
                ext = jsonObject {
                    "placement_id" hasValue "1004273176"
                    "payload" hasValue "test_payload_banner"
                }.toString(),
                timeout = 5000,
                uid = "uid_banner"
            )
        )
        assertThat(actual.placementId).isEqualTo("1004273176")
        assertThat(actual.payload).isEqualTo("test_payload_banner")
        assertThat(actual.price).isEqualTo(1.5)
        assertThat(actual.bannerFormat).isEqualTo(BannerFormat.Banner)
    }

    @Test
    fun `parse mrec AdUnit RTB`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 3.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 3.0,
                    label = "label_mrec",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "placement_id" hasValue "1004273177"
                        "payload" hasValue "test_payload_mrec"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_mrec"
                ),
                optBannerFormat = BannerFormat.MRec,
                optContainerWidth = 300f,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is ZmaticooBannerAuctionParams)
        assertThat(actual.adUnit).isEqualTo(
            AdUnit(
                demandId = "zmaticoo",
                pricefloor = 3.0,
                label = "label_mrec",
                bidType = BidType.RTB,
                ext = jsonObject {
                    "placement_id" hasValue "1004273177"
                    "payload" hasValue "test_payload_mrec"
                }.toString(),
                timeout = 5000,
                uid = "uid_mrec"
            )
        )
        assertThat(actual.placementId).isEqualTo("1004273177")
        assertThat(actual.payload).isEqualTo("test_payload_mrec")
        assertThat(actual.price).isEqualTo(3.0)
        assertThat(actual.bannerFormat).isEqualTo(BannerFormat.MRec)
    }

    @Test
    fun `parse AdUnit with missing placement_id returns failure result`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 1.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 1.0,
                    label = "label_no_placement",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "payload" hasValue "test_payload"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_no_placement"
                ),
                optBannerFormat = BannerFormat.Banner,
                optContainerWidth = 320f,
            )
        }
        val result = testee.getAuctionParam(auctionParamsScope)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `parse AdUnit with null ext returns null fields`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 1.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 1.0,
                    label = "label_null_ext",
                    bidType = BidType.RTB,
                    ext = null,
                    timeout = 5000,
                    uid = "uid_null_ext"
                ),
                optBannerFormat = BannerFormat.Banner,
                optContainerWidth = 320f,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is ZmaticooBannerAuctionParams)
        assertThat(actual.placementId).isNull()
        assertThat(actual.payload).isNull()
    }
}
