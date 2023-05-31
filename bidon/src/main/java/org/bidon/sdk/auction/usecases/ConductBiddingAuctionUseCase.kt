package org.bidon.sdk.auction.usecases

import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdSourceType
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResult

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal interface ConductBiddingAuctionUseCase {
    /**
     * @param participantIds Bidding Demand Ids
     */
    suspend fun invoke(
        context: Context,
        biddingSources: List<AdSourceType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd
    ): Result<AuctionResult>
}

internal class ConductBiddingAuctionUseCaseImpl(
    private val bidRequestUseCase: BidRequestUseCase
) : ConductBiddingAuctionUseCase {
    override suspend fun invoke(
        context: Context,
        biddingSources: List<AdSourceType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd
    ): Result<AuctionResult> = runCatching {
        val participants = biddingSources.filter {
            (it as AdSource<*>).demandId.demandId in participantIds
        }
        val tokens = participants.mapNotNull { adSource ->
            adSource.getToken(context)?.let { token ->
                (adSource as AdSource<*>).demandId to token
            }
        }
        val bidResponse = bidRequestUseCase.invoke(
            adTypeParam = adTypeParam,
            tokens = tokens,
            extras = demandAd.getExtras()
        ).getOrThrow()
        val payload = bidResponse.seatBid?.bids?.firstOrNull()

        TODO()
    }
}