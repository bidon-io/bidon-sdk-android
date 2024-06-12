package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult

/**
 * Created by Aleksei Cherniaev on 02/06/2023.
 */
internal interface ExecuteAuctionUseCase {
    suspend operator fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        adUnits: List<AdUnit>,
        resultsCollector: ResultsCollector,
    ): Result<List<AuctionResult>>
}
