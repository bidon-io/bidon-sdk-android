package org.bidon.vungle

import org.bidon.sdk.adapter.AdAuctionParams

object VungleBannerAuctionParams : AdAuctionParams {
    override val adUnitId: String?
        get() = TODO("Not yet implemented")
    override val price: Double
        get() = TODO("Not yet implemented")
}

class VungleFullscreenAuctionParams(
    override val price: Double,
    val placementId: String,
    val payload: String
) : AdAuctionParams {
    override val adUnitId: String
        get() = placementId
}
