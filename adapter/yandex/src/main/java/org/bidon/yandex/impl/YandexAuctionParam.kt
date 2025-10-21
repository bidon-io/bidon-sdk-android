package org.bidon.yandex.impl

import android.app.Activity
import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

internal class YandexBannerAuctionParam(
    val activity: Activity,
    val bannerFormat: BannerFormat,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val adUnitId: String? = adUnit.extra?.optString("ad_unit_id")
    val signalData: String? = adUnit.extra?.optString("signaldata")
}

internal class YandexFullscreenAuctionParam(
    val context: Context,
    override val adUnit: AdUnit,
) : AdAuctionParams {
    override val price: Double = adUnit.pricefloor
    val adUnitId: String? = adUnit.extra?.optString("ad_unit_id")
    val signalData: String? = adUnit.extra?.optString("signaldata")
}
