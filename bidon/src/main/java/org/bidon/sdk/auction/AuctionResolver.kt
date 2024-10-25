package org.bidon.sdk.auction

import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal interface AuctionResolver {
    suspend fun sortWinners(list: List<DemandResult>): List<DemandResult>
}
