package org.bidon.sdk.ads.cache.impl.alex

import org.bidon.sdk.auction.models.AuctionResult

internal sealed interface CacheItem {
    val auctionResult: AuctionResult

    data class FillEntry(
        override val auctionResult: AuctionResult,
        val price: Double,
    ) : CacheItem

    data class BidEntry(
        override val auctionResult: AuctionResult,
        val price: Double,
        val bidPayload: String,
    ) : CacheItem
}