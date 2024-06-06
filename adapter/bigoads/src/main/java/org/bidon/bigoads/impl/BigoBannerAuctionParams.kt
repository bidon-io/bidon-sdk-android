package org.bidon.bigoads.impl

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

data class BigoBannerAuctionParams(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    private val bidResponse: AdUnit,
) : AdAuctionParams {
    override val adUnit: AdUnit = bidResponse
    override val price: Double = bidResponse.pricefloor
    val payload: String = requireNotNull(bidResponse.extra?.getString("payload"))
    val slotId: String = requireNotNull(adUnit.extra?.getString("slot_id"))
}

data class BigoFullscreenAuctionParams(
    private val bidResponse: AdUnit,
) : AdAuctionParams {
    override val adUnit: AdUnit = bidResponse
    override val price: Double = bidResponse.pricefloor
    val payload: String = requireNotNull(bidResponse.extra?.getString("payload"))
    val slotId: String = requireNotNull(adUnit.extra?.getString("slot_id"))
}
