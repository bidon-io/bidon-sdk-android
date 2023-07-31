package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.json.JsonParser
import org.bidon.sdk.utils.serializer.JsonName
import org.bidon.sdk.utils.serializer.Serializable
import org.json.JSONObject

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal data class BiddingResponse(
    @field:JsonName("bids")
    val bids: List<BidResponse>?,
    @field:JsonName("status")
    val status: BidStatus,
) : Serializable {
    enum class BidStatus(val code: String) {
        Success("SUCCESS"),
        NoBid("NO_BID");

        companion object {
            fun get(code: String) = values().first { it.code == code }
        }
    }
}

internal class BidResponseParser : JsonParser<BiddingResponse> {
    override fun parseOrNull(jsonString: String): BiddingResponse? = runCatching {
        val json = JSONObject(jsonString)
        BiddingResponse(
            bids = json.optJSONArray("bids")?.let { array ->
                buildList {
                    repeat(array.length()) { index ->
                        array.optJSONObject(index)
                            ?.let { bidJson ->
                                val bid = BidResponse(
                                    id = bidJson.getString("id"),
                                    impressionId = bidJson.optString("impid"),
                                    price = bidJson.getDouble("price"),
                                    demands = bidJson.optJSONObject("demands")?.parseDemands() ?: emptyList()
                                )
                                add(bid)
                            }
                    }
                }
            },
            status = json.getString("status").let {
                BiddingResponse.BidStatus.get(it)
            }
        )
    }.getOrNull()

    private fun JSONObject.parseDemands(): List<BidDemandResponse> = buildList {
        this@parseDemands.keys().forEach { demandId ->
            runCatching {
                val json = this@parseDemands.getJSONObject(demandId)
                when (BiddingDemandName.getOrNull(demandId)) {
                    BiddingDemandName.Mintegral -> {
                        BidDemandResponse.Mintegral(
                            payload = json.getString("payload"),
                            unitId = json.getString("unit_id"),
                            placementId = json.getString("placement_id")
                        )
                    }

                    BiddingDemandName.BidMachine -> {
                        BidDemandResponse.BidMachine(
                            payload = json.getString("payload")
                        )
                    }

                    BiddingDemandName.Mobilefuse -> {
                        BidDemandResponse.Mobilefuse(
                            payload = json.getString("payload"),
                            placementId = json.getString("placement_id")
                        )
                    }

                    BiddingDemandName.Vungle -> {
                        BidDemandResponse.Vungle(
                            payload = json.getString("payload"),
                            placementId = json.getString("placement_id")
                        )
                    }

                    BiddingDemandName.BigoAds -> {
                        BidDemandResponse.BigoAds(
                            payload = json.getString("payload"),
                            slotId = json.getString("slot_id")
                        )
                    }

                    BiddingDemandName.Meta -> {
                        BidDemandResponse.Meta(
                            payload = json.getString("payload"),
                            placementId = json.getString("placement_id")
                        )
                    }

                    null -> error("Unknown demandId: $demandId")
                }
            }.getOrNull()?.let(::add)
        }
    }
}