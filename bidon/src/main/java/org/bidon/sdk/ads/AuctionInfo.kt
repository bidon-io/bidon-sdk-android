package org.bidon.sdk.ads

class AuctionInfo(
    val auctionId: String,
    val auctionConfigurationId: Long?,
    val auctionConfigurationUid: String?,
    val auctionTimeout: Long,
    val auctionPricefloor: Double,
    val noBids: List<AdUnitInfo>?,
    val adUnits: List<AdUnitInfo>?,
) {
    override fun toString(): String {
        return "AuctionInfo(auctionId='$auctionId', auctionConfigurationId=$auctionConfigurationId, auctionConfigurationUid=$auctionConfigurationUid, auctionTimeout=$auctionTimeout, auctionPricefloor=$auctionPricefloor, noBids=$noBids, adUnits=$adUnits)"
    }
}

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
    val ext: String?,
) {
    override fun toString(): String {
        return "AdUnitInfo(demandId='$demandId', label=$label, price=$price, uid=$uid, bidType=$bidType, fillStartTs=$fillStartTs, fillFinishTs=$fillFinishTs, status=$status, errorMessage=$errorMessage, ext=$ext)"
    }
}