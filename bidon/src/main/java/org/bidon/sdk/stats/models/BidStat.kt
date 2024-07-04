package org.bidon.sdk.stats.models

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.analytic.Precision

/**
 * Created by Bidon Team on 06/02/2023.
 */
data class BidStat(
    val auctionId: String?,
    val demandId: DemandId,
    val roundStatus: RoundStatus?,
    val ecpm: Double,
    val auctionPricefloor: Double,
    val fillStartTs: Long?,
    val fillFinishTs: Long?,
    val dspSource: String?,
    val precision: Precision,
    val adUnit: AdUnit?,

    /**
     * [Adapter.Bidding] only
     */
    val tokenInfo: TokenInfo?,
) {
    val bidType: BidType? get() = adUnit?.bidType
}