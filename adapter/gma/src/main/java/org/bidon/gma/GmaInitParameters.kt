package org.bidon.gma

import android.app.Activity
import com.google.android.gms.ads.AdSize
import org.bidon.gma.ext.toGmaAdSize
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

internal class GmaInitParameters(
    val requestAgent: String?,
    val queryInfoType: String?,
) : AdapterParameters

internal sealed interface GmaBannerAuctionParams : AdAuctionParams {
    val activity: Activity
    val bannerFormat: BannerFormat
    val containerWidth: Float
    val adSize: AdSize get() = bannerFormat.toGmaAdSize()

    class Network(
        override val activity: Activity,
        override val bannerFormat: BannerFormat,
        override val containerWidth: Float,
        override val adUnit: AdUnit,
    ) : GmaBannerAuctionParams {
        override val price: Double = adUnit.pricefloor
        val adUnitId: String? = adUnit.extra?.getString("ad_unit_id")

        override fun toString(): String {
            return "GmaBannerAuctionParams($adUnit)"
        }
    }
}

internal sealed interface GmaFullscreenAdAuctionParams : AdAuctionParams {
    val activity: Activity

    class Network(
        override val activity: Activity,
        override val adUnit: AdUnit,
    ) : GmaFullscreenAdAuctionParams {
        override val price: Double = adUnit.pricefloor
        val adUnitId: String? = adUnit.extra?.getString("ad_unit_id")

        override fun toString(): String {
            return "GmaFullscreenAdAuctionParams($adUnit)"
        }
    }
}
