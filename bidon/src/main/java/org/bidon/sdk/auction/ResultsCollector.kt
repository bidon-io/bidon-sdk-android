package org.bidon.sdk.auction

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.models.RoundResult

/**
 * Created by Aleksei Cherniaev on 05/07/2023.
 */
internal interface ResultsCollector {
    fun startRound(pricefloor: Double)
    fun serverBiddingStarted()
    fun serverBiddingFinished(bids: List<AdUnit>?)
    fun biddingTimeoutReached()
    fun add(result: AuctionResult)
    fun getRoundResults(): RoundResult

    fun getAll(): List<AuctionResult>
    fun clear()
    suspend fun saveWinners(sourcePriceFloor: Double)
    fun clearRoundResults()

    companion object {
        /**
         * How many succeeded result to hold
         */
        const val MaxAuctionResultsAmount = 2
    }
}
