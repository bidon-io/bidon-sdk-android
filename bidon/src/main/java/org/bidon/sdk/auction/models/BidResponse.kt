package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.json.JsonParser
import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable
import org.json.JSONObject

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 *
 * If error, it contains only [impressionId] and [noBiddingReason]
 *
 * No-Bid Reason Codes:
 * - 0 Unknown Error
 * - 1 Technical Error
 * - 2 Invalid Request
 * - 3 Known Web Spider
 * - 4 Suspected Non-Human Traffic
 * - 5 Cloud, Data center, or Proxy IP
 * - 6 Unsupported Device
 * - 7 Blocked Publisher or Site
 * - 8 Unmatched User
 * - 9 Daily Reader Cap Met
 * - 10 Daily Domain Cap Met
 */
internal data class BidResponse(
    @field:JsonName("id")
    val impressionId: String,
    @field:JsonName("seatbid")
    val seatBid: SeatBid?,
) : Serializable

internal class BidResponseParser : JsonParser<BidResponse> {
    override fun parseOrNull(jsonString: String): BidResponse? = runCatching {
        val json = JSONObject(jsonString)
        BidResponse(
            impressionId = json.getString("id"),
            seatBid = json.optJSONObject("seatbid")?.let {
                SeatBid(
                    bids = buildList {
                        val jsonArray = json.getJSONArray("bid")
                        repeat(jsonArray.length()) { index ->
                            val bidJson = jsonArray.getJSONObject(index)
                            add(
                                Bid(
                                    id = bidJson.getString("id"),
                                    payload = bidJson.getString("payload"),
                                    impressionId = bidJson.optString("impid"),
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
                            )
                        }
                    },
                    seat = json.optString("seat")
                )
            },
        )
    }.getOrNull()
}