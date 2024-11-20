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

    class Banner(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
        val bannerFormat: BannerFormat,
        val containerWidth: Float,
    ) : AdTypeParam {
        override fun toString(): String {
            return "Banner(activity=$activity, pricefloor=$pricefloor, auctionKey=$auctionKey, bannerFormat=$bannerFormat, containerWidth=$containerWidth)"
        }
    }

    class Interstitial(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
    ) : AdTypeParam {
        override fun toString(): String {
            return "Interstitial(activity=$activity, pricefloor=$pricefloor, auctionKey=$auctionKey)"
        }
    }

    class Rewarded(
        override val activity: Activity,
        override val pricefloor: Double,
        override val auctionKey: String?,
    ) : AdTypeParam {
        override fun toString(): String {
            return "Rewarded(activity=$activity, pricefloor=$pricefloor, auctionKey=$auctionKey)"
        }
    }
}
