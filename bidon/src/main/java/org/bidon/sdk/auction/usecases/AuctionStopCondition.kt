package org.bidon.sdk.auction.usecases

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

internal interface AuctionStopCondition {
    fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?,
    ): Boolean
}
