package org.bidon.sdk.auction

import android.app.Activity
import org.bidon.sdk.ads.banner.BannerFormat

/**
 * Created by Bidon Team on 06/02/2023.
 */
sealed interface AdTypeParam {
    val activity: Activity
    val pricefloor: Double
    val auctionKey: String?

    data class Banner(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
        val bannerFormat: BannerFormat,
        val containerWidth: Float,
    ) : AdTypeParam

    data class Interstitial(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
    ) : AdTypeParam

    data class Rewarded(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
    ) : AdTypeParam
}
