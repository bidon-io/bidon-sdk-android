package org.bidon.sdk.ads.cache.twolevel.storage

import org.bidon.sdk.auction.models.AuctionResult

internal sealed class InsertResult {
    /**
     * Item was inserted successfully.
     * @param evicted if non-null, the item that was evicted to make room (fallback cache only).
     *                Caller is responsible for loss notifications and destruction.
     */
    data class Success(val evicted: AuctionResult? = null) : InsertResult()
    data class Rejected(val reason: Reason) : InsertResult()

    enum class Reason {
        Threshold,
        StickyProtected,
        CacheFull,
    }

    val isInserted: Boolean get() = this is Success
}
