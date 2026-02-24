package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse

internal fun AuctionResponse.rtbAdUnits(): List<AdUnit>? = adUnits?.rtb()