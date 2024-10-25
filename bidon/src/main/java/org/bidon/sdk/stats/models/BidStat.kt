package org.bidon.sdk.stats.models

import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.auction.models.AdUnit

/**
 * Created by Bidon Team on 06/02/2023.
 */
data class BidStat(
    val auctionId: String?,
    val demandId: DemandId,
    val demandStatus: DemandStatus?,
    val ecpm: Double,
    val auctionPricefloor: Double,
    val fillStartTs: Long?,
    val fillFinishTs: Long?,
    val dsp: String?,
    val adUnit: AdUnit?,
) {
    val bidType: BidType? get() = adUnit?.bidType
}