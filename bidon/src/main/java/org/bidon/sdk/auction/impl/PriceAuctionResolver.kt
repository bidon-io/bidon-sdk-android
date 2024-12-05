package org.bidon.sdk.auction.impl

import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal val MaxEcpmAuctionResolver: AuctionResolver by lazy {
    PriceAuctionResolver()
}

private class PriceAuctionResolver : AuctionResolver {
    override suspend fun sortWinners(list: List<DemandResult>): List<DemandResult> {
        return list.sortedByDescending {
            when (it) {
                is DemandResult.Bidding -> it.adSource.getStats().ecpm
                is DemandResult.Network -> it.adSource.getStats().ecpm
                is DemandResult.DemandFailed -> it.adUnit.pricefloor
            }
        }
    }
}
