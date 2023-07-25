package org.bidon.bigoads.impl

import org.bidon.sdk.adapter.AdAuctionParams

data class BigoBannerAuctionParams(
    override val adUnitId: String,
    override val pricefloor: Double,
) : AdAuctionParams

data class BigoFullscreenAuctionParams(
    val slotId: String,
    override val pricefloor: Double,
) : AdAuctionParams {
    override val adUnitId: String get() = slotId
}
