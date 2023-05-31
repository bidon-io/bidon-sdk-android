package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName
import java.io.Serializable

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal data class SeatBid(
    @field:JsonName("bid")
    val bids: List<Bid>
) : Serializable