package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal interface GetAuctionRequestUseCase {
    suspend fun request(
        adTypeParam: AdTypeParam,
        auctionId: String,
        demandAd: DemandAd,
        tokens: Map<String, TokenInfo>,
    ): Result<AuctionResponse>
}
