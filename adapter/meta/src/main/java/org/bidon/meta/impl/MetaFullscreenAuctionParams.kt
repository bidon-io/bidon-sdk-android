package org.bidon.meta.impl

import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams

class MetaFullscreenAuctionParams(
    val context: Context,
    val placementId: String,
    val payload: String,
    override val price: Double
) : AdAuctionParams {
    override val adUnitId: String
        get() = placementId
}
