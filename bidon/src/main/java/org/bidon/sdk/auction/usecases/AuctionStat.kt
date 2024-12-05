package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.StatsRequestBody

/**
 * Created by Bidon Team on 09/06/2023.
 */
internal interface AuctionStat {
    fun markAuctionStarted(auctionId: String, adTypeParam: AdTypeParam)

    suspend fun addRoundResults(result: AuctionResult.Results): RoundStat

    fun sendAuctionStats(
        auctionData: AuctionResponse,
        roundStat: RoundStat?,
        demandAd: DemandAd
    ): StatsRequestBody?

    fun markAuctionCanceled()
}
