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

class ZmaticooRewardedImplTest {
    private val activity = mockk<Activity>()
    private val testee by lazy {
        ZmaticooRewardedImpl().apply {
            addDemandId(ZmaticooDemandId)
        }
    }

    @Test
    fun `implements required interfaces`() {
        assertThat(testee).isInstanceOf(AdEventFlow::class.java)
        assertThat(testee).isInstanceOf(StatisticsCollector::class.java)
    }

    @Test
    fun `parse rewarded AdUnit RTB`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 5.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 5.0,
                    label = "label_rewarded",
                    bidType = BidType.RTB,
                    ext = jsonObject {
                        "placement_id" hasValue "1003176365"
                        "payload" hasValue "test_payload_rewarded"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid_rewarded"
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
                pricefloor = 5.0,
                label = "label_rewarded",
                bidType = BidType.RTB,
                ext = jsonObject {
                    "placement_id" hasValue "1003176365"
                    "payload" hasValue "test_payload_rewarded"
                }.toString(),
                timeout = 5000,
                uid = "uid_rewarded"
            )
        )
        assertThat(actual.placementId).isEqualTo("1003176365")
        assertThat(actual.payload).isEqualTo("test_payload_rewarded")
        assertThat(actual.price).isEqualTo(5.0)
    }

    @Test
    fun `parse AdUnit with missing ext returns null fields`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 1.0,
                adUnit = AdUnit(
                    demandId = "zmaticoo",
                    pricefloor = 1.0,
                    label = "label_no_ext",
                    bidType = BidType.RTB,
                    ext = null,
                    timeout = 5000,
                    uid = "uid_no_ext"
                ),
                optBannerFormat = null,
                optContainerWidth = null,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is ZmaticooFullscreenAuctionParams)
        assertThat(actual.placementId).isNull()
        assertThat(actual.payload).isNull()
    }
}
