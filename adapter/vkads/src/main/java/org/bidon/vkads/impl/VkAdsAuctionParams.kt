package org.bidon.vkads.impl

import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

internal class VkAdsFullscreenAuctionParams(
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}

internal class VkAdsViewAuctionParams(
    val context: Context,
    override val adUnit: AdUnit,
    val bannerFormat: BannerFormat,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}