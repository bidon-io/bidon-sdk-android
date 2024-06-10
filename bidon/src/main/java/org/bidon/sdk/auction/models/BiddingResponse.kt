package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
@Deprecated("")
internal data class BiddingResponse(
    @field:JsonName("bids")
    val bids: List<AdUnit>?,
    @field:JsonName("status")
    val status: BidStatus,
) : Serializable {
    @Deprecated("")
    enum class BidStatus(val code: String) {
        Success("SUCCESS"),
        NoBid("NO_BID");
    }
}