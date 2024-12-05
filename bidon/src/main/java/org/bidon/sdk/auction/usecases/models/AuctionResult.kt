package org.bidon.sdk.auction.usecases.models

import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 26/07/2023.
 */
internal sealed interface AuctionResult {
    object Idle : AuctionResult

    class Results(
        val pricefloor: Double,
        val serverBiddingResult: ServerBiddingResult,
        val demandResults: List<DemandResult>,
    ) : AuctionResult
}