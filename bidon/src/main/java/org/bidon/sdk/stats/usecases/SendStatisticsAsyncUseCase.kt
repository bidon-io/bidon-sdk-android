package org.bidon.sdk.stats.usecases

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.stats.RoundStat

/**
 * Created by Aleksei Cherniaev on 05/06/2023.
 */
internal interface SendStatisticsAsyncUseCase {
    operator fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        auctionStartTs: Long,
        auctionFinishTs: Long,
        statsAuctionResults: List<AuctionResult>,
        statsRound: List<RoundStat>,
    )
}
