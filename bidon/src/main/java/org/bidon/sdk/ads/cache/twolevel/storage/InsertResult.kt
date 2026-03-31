package org.bidon.sdk.ads.cache.twolevel.storage

import org.bidon.sdk.auction.models.AuctionResult

internal sealed class InsertResult {
    data class Success(val evicted: List<AuctionResult> = emptyList()) : InsertResult()
    data class Rejected(val reason: Reason) : InsertResult()

    enum class Reason {
        IterationThreshold,
        StickyHeadProtected,
        CacheFull,
    }

    val isInserted: Boolean get() = this is Success
}
