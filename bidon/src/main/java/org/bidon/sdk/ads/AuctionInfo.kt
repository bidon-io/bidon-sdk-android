package org.bidon.sdk.ads

import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.StatsAdUnit
import org.json.JSONObject

class AuctionInfo(
    val auctionId: String,
    val auctionConfigurationId: Long?,
    val auctionConfigurationUid: String?,
    val auctionPricefloor: Double,
    val noBids: List<BidsInfo>?,
    val adUnits: List<AdUnitInfo>?,
)
class BidsInfo(
    val demandId: String,
    val label: String?,
    val price: Double?,
    val uid: String?,
    val status: String = RoundStatus.NoBid.code,
    val bidType: String?,
    val ext: JSONObject?,
)
class AdUnitInfo(
    val demandId: String,
    val label: String?,
    val price: Double?,
    val uid: String?,
    val bidType: String?,
    val fillStartTs: Long?,
    val fillFinishTs: Long?,
    val status: String?,
    val errorMessage: String? = null,
    val ext: JSONObject?,
)

internal fun StatsAdUnit.toPublicApi() =
    AdUnitInfo(
        demandId = demandId,
        label = adUnitLabel,
        price = price,
        uid = adUnitUid,
        bidType = bidType,
        fillStartTs = fillStartTs,
        fillFinishTs = fillFinishTs,
        status = status,
        errorMessage = errorMessage,
        ext = ext,
    )