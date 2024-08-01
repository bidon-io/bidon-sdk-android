package org.bidon.vkads.impl

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

internal class VkAdsFullscreenAuctionParams(
    val activity: Activity,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}

internal class VkAdsViewAuctionParams(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}