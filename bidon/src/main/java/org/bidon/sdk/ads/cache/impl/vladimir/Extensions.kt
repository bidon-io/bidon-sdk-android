package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

internal val AuctionResult.demandId: String
    get() = adSource.getStats().demandId.demandId

internal val AuctionResult.price: Double
    get() = adSource.getStats().price

/**
 * An untried ad unit paired with the auction round that produced it.
 * Each unit retains its originating round context (auctionId, config, tokens)
 * so stats are reported correctly even when units accumulate across rounds.
 */
internal data class RemainingUnit(
    val adUnit: AdUnit,
    val round: WaterfallLoader.AuctionRound,
)

/**
 * A cached ad paired with the auction info from the round that produced it.
 * Used to preserve auction metadata across slot operations and instance recreations.
 */
internal data class CachedAd(
    val result: AuctionResult,
    val auctionInfo: AuctionInfo,
)
