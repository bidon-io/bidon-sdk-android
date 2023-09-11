package org.bidon.inmobi.impl

import android.app.Activity
import org.bidon.sdk.adapter.AdAuctionParams

class InmobiFullscreenAuctionParams(
    val activity: Activity,
    val placementId: Long,
    override val price: Double
) : AdAuctionParams {
    override val adUnitId: String get() = placementId.toString()

    override fun toString(): String {
        return "InmobiFullscreenAuctionParams(placementId=$placementId, price=$price)"
    }
}
