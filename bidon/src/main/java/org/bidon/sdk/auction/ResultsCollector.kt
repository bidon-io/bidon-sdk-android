package org.bidon.sdk.auction

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.models.AuctionResult

/**
 * Created by Bidon Team on 05/07/2023.
 */
internal interface ResultsCollector {
    fun startAuction(pricefloor: Double)

    fun serverBiddingStarted()
    fun serverBiddingFinished(tokens: Map<String, TokenInfo>, noBids: List<AdUnit>?)

    fun add(result: DemandResult)
    fun getRoundResults(): AuctionResult

    fun getAll(): List<DemandResult>
    fun clear()

    suspend fun finishAuction(pricefloor: Double)

    companion object {
        /**
         * How many succeeded result to hold
         */
        const val MaxAuctionResultsAmount = 2
    }
}
