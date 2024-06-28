package org.bidon.sdk.auction.models

import org.bidon.sdk.ads.BidsInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.json.JSONObject

/**
 * Created by Aleksei Cherniaev on 24/10/2023.
 */
data class AdUnit(
    val demandId: String,
    val label: String,
    val pricefloor: Double,
    val uid: String,
    val bidType: BidType,
    val timeout: Long,
    private val ext: String?,
) {
    val extra: JSONObject? = ext?.let {
        JSONObject(it)
    }

    override fun toString() =
        "Demand: $demandId, Pricefloor: $pricefloor, UID: $uid, BidType: $bidType"
}

internal fun AdUnit.toBidsInfo(startBidding: Long, finishBidding: Long) =
    BidsInfo(
        demandId = demandId,
        label = label,
        price = pricefloor,
        uid = uid,
        bidType = bidType.code,
        fillStartTs = startBidding,
        fillFinishTs = finishBidding,
        status = RoundStatus.NoBid.code,
        ext = extra,
    )
