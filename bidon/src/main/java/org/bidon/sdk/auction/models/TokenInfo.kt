package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable

/**
 * Created by Aleksei Cherniaev on 14/11/2023.
 */
data class TokenInfo(
    @field:JsonName("token")
    val token: String?,
    val tokenStartTs: Long?,
    val tokenFinishTs: Long?,
    val status: String,
) : Serializable {
    enum class Status(val code: String) {
        SUCCESS("SUCCESS"),
        TIMEOUT_REACHED("TIMEOUT_REACHED"),
        NO_TOKEN("NO_TOKEN"),
        @Deprecated("Because tokens collect without ad unit id")
        NO_APPROPRIATE_AD_UNIT_ID("NO_APPROPRIATE_AD_UNIT_ID"),
        @Deprecated("Because tokens collect for available ad adapters")
        UNKNOWN_ADAPTER("UNKNOWN_ADAPTER");
    }
}
