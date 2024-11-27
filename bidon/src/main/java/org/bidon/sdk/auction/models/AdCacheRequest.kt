package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Bidon Team on 26/11/2024.
 */
internal data class AdCacheRequest(
    @field:JsonName("demand_id")
    val demandId: String,
    @field:JsonName("price")
    val price: Double,
    @field:JsonName("timestamp")
    val timestamp: Long?,
    @field:JsonName("uid")
    val uid: String?,
) : Serializable