package org.bidon.sdk.config.models

import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.json.JsonParser
import org.json.JSONObject

internal interface BiddingConfig {
    var tokenTimeout: Long
}

internal class BiddingConfigImpl(override var tokenTimeout: Long = 20000): BiddingConfig

internal class BiddingResponseParser : JsonParser<BiddingConfig> {
    override fun parseOrNull(jsonString: String): BiddingConfig? = runCatching {
        val json = JSONObject(jsonString)
        get<BiddingConfig>().apply {
            tokenTimeout = json.getLong("token_timeout_ms")
        }
    }.getOrNull()
}