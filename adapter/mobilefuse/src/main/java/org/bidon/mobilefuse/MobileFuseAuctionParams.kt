package org.bidon.mobilefuse

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.auction.models.LineItem

/**
 * Created by Aleksei Cherniaev on 21/09/2023.
 */
class MobileFuseAuctionParams(
    val activity: Activity,
    val payload: String,
    override val price: Double
) : AdAuctionParams {
    override val lineItem: LineItem? = null
}