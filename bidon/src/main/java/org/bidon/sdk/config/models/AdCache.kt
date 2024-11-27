package org.bidon.sdk.config.models

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Bidon Team on 26/11/2024.
 */
internal data class AdCache(
    @field:JsonName("demand_id")
    val demandId: String,
    @field:JsonName("timestamp")
    val timestamp: Long,
    @field:JsonName("price")
    val price: Double,
    @field:JsonName("ad_unit_id")
    val adUnitId: String,
) : Serializable