package org.bidon.sdk.ads

import org.json.JSONObject

class AuctionInfo(
    val auctionId: String,
    val auctionConfigurationId: Long?,
    val auctionConfigurationUid: String?,
    val auctionPricefloor: Double,
    val noBids: List<BidsInfo>,
    val adUnits: List<AdUnitInfo>,
)
class BidsInfo(
    val demandId: String,
    val label: String?,
    val price: Double?,
    val uid: String?,
    val bidType: String?,
    val fillStartTs: Long?,
    val fillFinishTs: Long?,
    val status: String?,
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
    val ext: JSONObject?,
)
