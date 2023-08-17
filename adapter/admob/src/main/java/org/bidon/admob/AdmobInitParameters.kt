package org.bidon.admob

import android.content.Context
import com.google.android.gms.ads.AdSize
import org.bidon.admob.ext.toAdmobAdSize
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.LineItem

object AdmobInitParameters : AdapterParameters

sealed interface AdmobBannerAuctionParams : AdAuctionParams {
    val bannerFormat: BannerFormat
    val context: Context
    val containerWidth: Float
    val adSize: AdSize get() = bannerFormat.toAdmobAdSize(context, containerWidth)

    class Network(
        override val context: Context,
        override val bannerFormat: BannerFormat,
        override val containerWidth: Float,
        val lineItem: LineItem,
    ) : AdmobBannerAuctionParams {
        override val adUnitId: String? get() = lineItem.adUnitId
        override val price: Double get() = lineItem.pricefloor

        override fun toString(): String {
            return "AdmobBannerAuctionParams($lineItem)"
        }
    }

    class Bidding(
        override val context: Context,
        override val bannerFormat: BannerFormat,
        override val containerWidth: Float,
        override val price: Double,
        val payload: String,
        val unitId: String
    ) : AdmobBannerAuctionParams {
        override val adUnitId: String get() = unitId

        override fun toString(): String {
            return "AdmobBannerAuctionParams($unitId, bidPrice=$price)"
        }
    }
}

class AdmobFullscreenAdAuctionParams(
    val context: Context,
    val lineItem: LineItem,
    val payload: String?,
    override val adUnitId: String
) : AdAuctionParams {

    override val price: Double get() = lineItem.pricefloor

    override fun toString(): String {
        return "AdmobFullscreenAdAuctionParams(lineItem=$lineItem)"
    }
}
