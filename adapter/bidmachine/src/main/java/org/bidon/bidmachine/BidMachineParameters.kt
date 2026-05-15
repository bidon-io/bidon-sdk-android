package org.bidon.bidmachine

import android.app.Activity
import android.content.Context
import io.bidmachine.CustomParams
import io.bidmachine.TargetingParams
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.AdUnit

public data class BidMachineParameters(
    val sellerId: String,
    val endpoint: String?,
    val placements: Map<String, String>?,
) : AdapterParameters

public class BMBannerAuctionParams(
    override val price: Double,
    override val adUnit: AdUnit,
    public val activity: Activity,
    public val bannerFormat: BannerFormat,
    public val timeout: Long,
    public val customParameters: CustomParams,
    public val targetingParams: TargetingParams,
    public val payload: String?,
    public val placement: String?,
) : AdAuctionParams {

    override fun toString(): String {
        return "BMBannerAuctionParams(bannerFormat=$bannerFormat, pricefloor=$price, timeout=$timeout)"
    }
}

public class BMFullscreenAuctionParams(
    override val price: Double,
    override val adUnit: AdUnit,
    public val context: Context,
    public val timeout: Long,
    public val customParameters: CustomParams,
    public val targetingParams: TargetingParams,
    public val payload: String?,
    public val placement: String?,
) : AdAuctionParams {

    override fun toString(): String {
        return "BMFullscreenAuctionParams(pricefloor=$price, timeout=$timeout)"
    }
}
