package org.bidon.inmobi.impl

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat

class InmobiBannerAuctionParams(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    val placementId: Long,
    override val price: Double
) : AdAuctionParams {
    override val adUnitId: String get() = placementId.toString()

    override fun toString(): String {
        return "InmobiBannerAuctionParams($bannerFormat, placementId=$placementId, price=$price)"
    }
}
