package org.bidon.mintegral

import android.app.Activity
import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.banner.BannerFormat

/**
 * Created by Aleksei Cherniaev on 20/06/2023.
 */
class MintegralAuctionParam(
    val activity: Activity,
    override val pricefloor: Double,
    override val adUnitId: String?,
    val payload: String,
    val placementId: String?,
) : AdAuctionParams

class MintegralBannerAuctionParam(
    val context: Context,
    val bannerFormat: BannerFormat,
    override val pricefloor: Double,
    override val adUnitId: String?,
    val payload: String,
    val placementId: String?,
) : AdAuctionParams