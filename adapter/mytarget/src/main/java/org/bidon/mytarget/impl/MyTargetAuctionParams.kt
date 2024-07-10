package org.bidon.mytarget.impl

import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

class MyTargetFullscreenAuctionParams(
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}

class MyTargetViewAuctionParams(
    val context: Context,
    override val adUnit: AdUnit,
    val bannerFormat: BannerFormat,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val mediation = adUnit.extra?.optString("mediation")
    val slotId: Int? = adUnit.extra?.optInt("slot_id")
}