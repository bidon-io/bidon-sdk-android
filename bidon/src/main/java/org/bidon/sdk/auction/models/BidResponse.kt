package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.json.JsonParser
import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable
import org.json.JSONObject

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal data class BidResponse(
    @field:JsonName("bid")
    val bid: Bid?,
) : Serializable

internal class BidResponseParser : JsonParser<BidResponse> {
    override fun parseOrNull(jsonString: String): BidResponse? = runCatching {
        val json = JSONObject(jsonString)
        BidResponse(
            bid = json.getJSONObject("bid")?.let { bidJson ->
                Bid(
                    id = bidJson.getString("id"),
                    impressionId = bidJson.optString("impid"),
                    payload = bidJson.getString("payload"),
                    winNoticeUrl = bidJson.optString("nurl"),
                    billingNoticeUrl = bidJson.optString("burl"),
                    lossNoticeUrl = bidJson.optString("lurl"),
                    demandId = bidJson.optString("demand_id"),
                    price = bidJson.getDouble("price"),
                    ext = bidJson.optJSONObject("ext")?.let { extJson ->
                        buildMap<String, Any> {
                            extJson.keys().forEach { key ->
                                extJson.optJSONObject(key)?.let { put(key, it) }
                            }
                        }
                    } ?: emptyMap()
                )
            }
        )
    }.getOrNull()
}