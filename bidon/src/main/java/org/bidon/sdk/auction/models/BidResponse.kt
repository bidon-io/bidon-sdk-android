package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.json.JsonParser
import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable
import org.json.JSONObject

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal data class BidResponse(
    @field:JsonName("id")
    val impressionId: String,
    @field:JsonName("bid")
    val bid: Bid?,
) : Serializable

internal class BidResponseParser : JsonParser<BidResponse> {
    override fun parseOrNull(jsonString: String): BidResponse? = runCatching {
        val json = JSONObject(jsonString)
        BidResponse(
            impressionId = json.getString("id"),
            bid = json.getJSONObject("bid")?.let { bidJson ->
                Bid(
                    id = bidJson.getString("id"),
                    payload = bidJson.getString("payload"),
                    impressionId = bidJson.optString("impid"),
                    demandId = bidJson.optString("demand_id"),
                    winNoticeUrl = bidJson.optString("nurl"),
                    billingNoticeUrl = bidJson.optString("burl"),
                    lossNoticeUrl = bidJson.optString("lurl"),
                    price = bidJson.getDouble("price"),
                    ext = bidJson.optJSONObject("ext").let { extJson ->
                        buildMap<String, Any> {
                            extJson?.keys()?.forEach { key ->
                                extJson.optJSONObject(key)?.let { put(key, it) }
                            }
                        }
                    }
                )
            }
        )
    }.getOrNull()
}