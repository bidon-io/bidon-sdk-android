package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.stats.models.BidType

internal fun List<AdUnit>.rtb(): List<AdUnit> = filter { it.bidType == BidType.RTB }

internal fun AuctionResponse.rtbAdUnits(): List<AdUnit>? = adUnits?.rtb()
