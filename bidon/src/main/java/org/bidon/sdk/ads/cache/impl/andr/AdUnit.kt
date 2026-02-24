package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType

internal fun List<AdUnit>.rtb(): List<AdUnit> = filter { it.bidType == BidType.RTB }