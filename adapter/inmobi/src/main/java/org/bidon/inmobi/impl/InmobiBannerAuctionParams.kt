package org.bidon.inmobi.impl

import org.bidon.sdk.adapter.AdAuctionParams

class InmobiBannerAuctionParams(
    override val adUnitId: String?,
    override val price: Double
) : AdAuctionParams