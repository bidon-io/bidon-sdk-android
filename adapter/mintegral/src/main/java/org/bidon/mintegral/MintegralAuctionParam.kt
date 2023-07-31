package org.bidon.mintegral

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams

/**
 * Created by Aleksei Cherniaev on 20/06/2023.
 */
class MintegralAuctionParam(
    val activity: Activity,
    override val pricefloor: Double,
    override val adUnitId: String?,
    val payload: String,
    val placementId: String?,
) : AdAuctionParams {
}