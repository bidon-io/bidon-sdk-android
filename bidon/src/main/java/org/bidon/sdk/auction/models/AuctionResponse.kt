package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.json.JsonParser
import org.bidon.sdk.utils.json.JsonParsers
import org.json.JSONObject

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal data class AuctionResponse(
    val adUnits: List<AdUnit>?,
    val pricefloor: Double?,
    val token: String?,
    val auctionId: String,
    val auctionTimeout: Long,
    val auctionConfigurationId: Long?,
    val auctionConfigurationUid: String?,
    val externalWinNotificationsEnabled: Boolean,
)

internal class AuctionResponseParser : JsonParser<AuctionResponse> {
    override fun parseOrNull(jsonString: String): AuctionResponse? = runCatching {
        //TODO return jsonString
        val json = JSONObject(mockAuctionResponse)
        AuctionResponse(
            adUnits = JsonParsers.parseList(json.optJSONArray("ad_units")),
            pricefloor = json.optDouble("auction_pricefloor"),
            token = json.optString("token"),
            auctionId = json.getString("auction_id"),
            auctionTimeout = json.optLong("auction_timeout", defaultTimeout),
            auctionConfigurationId = json.optLong("auction_configuration_id"),
            auctionConfigurationUid = json.optString("auction_configuration_uid"),
            externalWinNotificationsEnabled = json.optBoolean("external_win_notifications", false),
        )
    }.getOrNull()
}

//TODO
private val defaultTimeout = 10000L

// TODO please, remove me!
private val mockAuctionResponse = "{\n" +
        "  \"auction_configuration_id\": 11,\n" +
        "  \"auction_configuration_uid\": \"1633777377077231616\",\n" +
        "  \"ad_units\": [\n" +
        "    {\n" +
        "      \"demand_id\": \"bidmachine\",\n" +
        "      \"uid\": \"1726915010958987264\",\n" +
        "      \"label\": \"bm_interstitial_rtb\",\n" +
        "      \"bid_type\": \"RTB\",\n" +
        "      \"ext\": {\n" +
        "        \"payload\": \"d9cad3e2-5cb8-4bb2-81a3-11140ea6dfd8\"\n" +
        "      }\n" +
        "    }\n" +
        "  ],\n" +
        "  \"segment\": {\n" +
        "    \"id\": \"\",\n" +
        "    \"uid\": \"\"\n" +
        "  },\n" +
        "  \"token\": \"{}\",\n" +
        "  \"auction_pricefloor\": 0,\n" +
        "  \"auction_timeout\": 30000,\n" +
        "  \"auction_id\": \"c82e7ed5-efdc-45f6-a629-84247e5aa649\"\n" +
        "}"
