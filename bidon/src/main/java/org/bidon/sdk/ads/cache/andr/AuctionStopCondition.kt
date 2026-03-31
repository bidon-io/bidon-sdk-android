package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

internal interface AuctionStopCondition {
    fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?,
    ): Boolean
}
