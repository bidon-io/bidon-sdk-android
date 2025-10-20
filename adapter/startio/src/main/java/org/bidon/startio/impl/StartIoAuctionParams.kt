package org.bidon.startio.impl

import android.app.Activity
import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

internal class StartIoFullscreenAuctionParams(
    val context: Context,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val tag: String? = adUnit.extra?.optString("tag_id")
    val payload: String? = adUnit.extra?.optString("payload")
}

internal class StartIoBannerAuctionParams(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val payload: String? = adUnit.extra?.optString("payload")
    val tag: String? = adUnit.extra?.optString("tag_id")
    val bannerSize
        get() = when (bannerFormat) {
            BannerFormat.MRec -> Pair(300, 250)
            else -> Pair(320, 50)
        }
}
