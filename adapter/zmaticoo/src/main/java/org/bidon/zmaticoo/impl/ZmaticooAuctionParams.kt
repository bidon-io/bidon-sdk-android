package org.bidon.zmaticoo.impl

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

/**
 * Created by Vladimir Khrolovich on 09/01/2026.
 */
internal class ZmaticooFullscreenAuctionParams(
    val activity: Activity,
    override val adUnit: AdUnit
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val placementId: String? = adUnit.extra?.getString("placement_id")
    val payload: String? = adUnit.extra?.getString("payload")

    override fun toString(): String {
        return "ZmaticooFullscreenAuctionParams(placementId=$placementId, payload=$payload, price=$price)"
    }
}

internal class ZmaticooBannerAuctionParams(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    override val adUnit: AdUnit
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val placementId: String? = adUnit.extra?.getString("placement_id")
    val payload: String? = adUnit.extra?.getString("payload")

    override fun toString(): String {
        return "ZmaticooBannerAuctionParams(placementId=$placementId, payload=$payload, bannerFormat=$bannerFormat, price=$price)"
    }
}
