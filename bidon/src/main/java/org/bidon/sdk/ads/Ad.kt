package org.bidon.sdk.ads

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType

/**
 * Created by Bidon Team on 06/02/2023.
 */
class Ad(
    val ecpm: Double,
    val auctionId: String,
    val dsp: String?,
    val currencyCode: String?,
    val adUnit: AdUnit
) {
    // Monetization Network name
    val networkName: String
        get() = adUnit.demandId

    val bidType: BidType
        get() = adUnit.bidType

    override fun toString(): String {
        return "Ad(networkName='$networkName', bidType=$bidType, dsp=$dsp, ecpm=$ecpm, currencyCode=$currencyCode)"
    }
}