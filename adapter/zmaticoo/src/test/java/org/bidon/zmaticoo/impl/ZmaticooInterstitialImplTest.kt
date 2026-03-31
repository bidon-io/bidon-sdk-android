package org.bidon.zmaticoo.impl

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import org.bidon.zmaticoo.ZmaticooDemandId
import org.junit.Test

class ZmaticooInterstitialImplTest {
    private val activity = mockk<Activity>()
    private val testee by lazy {
        ZmaticooInterstitialImpl().apply {
            addDemandId(ZmaticooDemandId)
        }
    }

    @Test
    fun `implements required interfaces`() {
        assertThat(testee).isInstanceOf(AdEventFlow::class.java)
        assertThat(testee).isInstanceOf(StatisticsCollector::class.java)
    }

    @Test
    fun `parse interstitial AdUnit RTB`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 4.5,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 4.5,
                    label = "label_interstitial",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "placement_id" hasValue "1004273226"
                        "payload" hasValue "test_payload_interstitial"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_interstitial"
                ),
                optBannerFormat = null,
                optContainerWidth = null,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is ZmaticooFullscreenAuctionParams)
        assertThat(actual.adUnit).isEqualTo(
            AdUnit(
                demandId = "zmaticoo",
                pricefloor = 4.5,
                label = "label_interstitial",
                bidType = BidType.RTB,
                ext = jsonObject {
                    "placement_id" hasValue "1004273226"
                    "payload" hasValue "test_payload_interstitial"
                }.toString(),
                timeout = 5000,
                uid = "uid_interstitial"
            )
        )
        assertThat(actual.placementId).isEqualTo("1004273226")
        assertThat(actual.payload).isEqualTo("test_payload_interstitial")
        assertThat(actual.price).isEqualTo(4.5)
    }

    @Test
    fun `parse AdUnit with missing payload returns failure result`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 2.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 2.0,
                    label = "label_no_payload",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "placement_id" hasValue "1004273226"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_no_payload"
                ),
                optBannerFormat = null,
                optContainerWidth = null,
            )
        }
        val result = testee.getAuctionParam(auctionParamsScope)

        assertThat(result.isFailure).isTrue()
    }
}
