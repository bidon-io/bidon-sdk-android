package org.bidon.unityads.impl

import android.app.Activity
import android.view.ViewGroup
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.LineItem

/**
 * Created by Aleksei Cherniaev on 02/03/2023.
 */
data class UnityAdsFullscreenAuctionParams(
    val lineItem: LineItem,
    val pricefloor: Double
) : AdAuctionParams {
    override val adUnitId: String get() = requireNotNull(lineItem.adUnitId)
}

data class UnityAdsBannerAuctionParams(
    val activity: Activity,
    val adContainer: ViewGroup,
    val bannerFormat: BannerFormat,
    val lineItem: LineItem,
    val pricefloor: Double
) : AdAuctionParams {
    override val adUnitId: String get() = requireNotNull(lineItem.adUnitId)
}
