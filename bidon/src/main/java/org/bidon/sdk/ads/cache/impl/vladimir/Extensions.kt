package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.models.AuctionResult

internal val AuctionResult.demandId: String
    get() = adSource.getStats().demandId.demandId

internal val AuctionResult.price: Double
    get() = adSource.getStats().price

/**
 * A cached ad paired with the auction info from the round that produced it.
 * Used to preserve auction metadata across slot operations and instance recreations.
 */
internal data class CachedAd(
    val result: AuctionResult,
    val auctionInfo: AuctionInfo,
)
