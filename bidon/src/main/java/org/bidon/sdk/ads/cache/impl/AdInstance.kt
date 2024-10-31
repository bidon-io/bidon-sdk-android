package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.utils.ext.SystemTimeNow

internal data class AdInstance(
    val adSource: AdSource<*>,
    val auctionInfo: AuctionInfo,
) {
    val ecpm: Double get() = adSource.getStats().ecpm
    val timestamp: Long = SystemTimeNow
}