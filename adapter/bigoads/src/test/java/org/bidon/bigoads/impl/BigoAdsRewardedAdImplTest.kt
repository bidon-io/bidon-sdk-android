package org.bidon.bigoads.impl

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.bidon.bigoads.BigoAdsDemandId
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 21/11/2023.
 */
class BigoAdsRewardedAdImplTest {
    private val activity = mockk<Activity>()
    private val testee by lazy {
        BigoAdsRewardedAdImpl().apply {
            addDemandId(BigoAdsDemandId)
        }
    }

    @Test
    fun `parse banner AdUnit RTB`() {
        val auctionParamsScope by lazy {
            AdAuctionParamSource(
                activity = activity,
                pricefloor = 2.5,
                timeout = 1000,
                AdUnit(
                    demandId = "admob",
                    pricefloor = 3.5,
                    label = "label888",
                    bidType = BidType.CPM,
                    ext = jsonObject {
                        "ad_unit_id" hasValue "ad_unit_id888"
                    }.toString(),
                    timeout = 5000,
                    uid = "uid123"
                ),

                optBannerFormat = BannerFormat.MRec,
                optContainerWidth = 140f,
            )
        }
        val actual = testee.getAuctionParam(auctionParamsScope).getOrThrow()

        require(actual is BigoFullscreenAuctionParams)
        assertThat(actual.adUnit).isEqualTo(
            AdUnit(
                demandId = "bigoads",
                pricefloor = 2.7,
                label = "label123",
                bidType = BidType.RTB,
                ext = jsonObject {
                    "slot_id" hasValue "slot_id4"
                }.toString(),
                timeout = 5000,
                uid = "uid123"
            )
        )
        assertThat(actual.slotId).isEqualTo("slot_id4")
        assertThat(actual.payload).isEqualTo("payload123")
        assertThat(actual.price).isEqualTo(2.75)
    }
}