package org.bidon.sdk.auction.usecases.impl

import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.BiddingResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.BidRequestUseCase
import org.bidon.sdk.utils.ext.asSuccess

@Deprecated("")
internal class BidRequestUseCaseImpl : BidRequestUseCase {
    @Deprecated("")
    override suspend fun invoke(
        adTypeParam: AdTypeParam,
        tokens: List<Pair<String, TokenInfo>>,
        extras: Map<String, Any>,
        bidfloor: Double,
        auctionId: String,
        auctionConfigurationId: Long?,
        auctionConfigurationUid: String?,
    ): Result<BiddingResponse> {
        return BiddingResponse(
            bids = emptyList(),
            status = BiddingResponse.BidStatus.Success,
        ).asSuccess()
    }
}
