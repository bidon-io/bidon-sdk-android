package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 *
 * @param orientationCode is [AdObjectRequestBody.Orientation.code]
 */
internal data class BidRequestBody(
    @field:JsonName("id")
    val impressionId: String,
    @field:JsonName("orientation")
    val orientationCode: String,
    @field:JsonName("banner")
    val banner: BannerRequestBody?,
    @field:JsonName("bidfloor")
    val bidfloor: Double,
    @field:JsonName("ext")
    val extras: Map<String, Any>,
) : Serializable {

    data class BidonExtras(
        @field:JsonName("bidding")
        val map: Map<String, Token>
    ) : Serializable

    data class Token(
        @field:JsonName("token")
        val token: String
    ) : Serializable

}