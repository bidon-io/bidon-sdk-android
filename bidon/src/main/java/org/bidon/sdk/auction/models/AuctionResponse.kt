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
        "  \"external_win_notifications\": true,\n" +
        "  \"ad_units\": [\n" +
        "    {\n" +
        "      \"demand_id\": \"meta\",\n" +
        "      \"label\": \"meta\",\n" +
        "      \"uid\": \"1688895618619146240\",\n" +
        "      \"pricefloor\": 0.1,\n" +
        "      \"ad_unit_id\": \"767803077426274_1212622446277666\",\n" +
        "      \"placement_id\": \"767803077426274_1212622446277666\",\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\",\n" +
        "      \"ext\": {\n" +
        "        \"payload\": \"12345678901234567890123456789012\"\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"mintegral\",\n" +
        "      \"label\": \"mintegral\",\n" +
        "      \"uid\": \"1686341534611537920\",\n" +
        "      \"pricefloor\": 1.3,\n" +
        "      \"ad_unit_id\": \"2567723\",\n" +
        "      \"placement_id\": \"938177\",\n" +
        "      \"bid_type\": \"RTB\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\",\n" +
        "      \"ext\": {\n" +
        "        \"payload\": \"12345678901234567890123456789012\"\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"bidmachine\",\n" +
        "      \"label\": \"bidmachine\",\n" +
        "      \"uid\": \"1718930569917632512\",\n" +
        "      \"pricefloor\": 10000,\n" +
        "      \"bid_type\": \"RTB\",\n" +
        "      \"ad_unit_id\": \"\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\",\n" +
        "      \"ext\": {\n" +
        "        \"payload\": \"12345678901234567890123456789012\"\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1633840817238048768\",\n" +
        "      \"pricefloor\": 15,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"5f3993e38b1a8f09\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"5f3993e38b1a8f09\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1633840483748937728\",\n" +
        "      \"pricefloor\": 5,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bb349fe9593c88ca\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"bb349fe9593c88ca\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1633840597708177408\",\n" +
        "      \"pricefloor\": 7,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"74d8bc1ee2152569\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"74d8bc1ee2152569\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1633840718311194624\",\n" +
        "      \"pricefloor\": 10,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"136e4dc0ea23e684\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"136e4dc0ea23e684\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677286529032126464\",\n" +
        "      \"pricefloor\": 11,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"5e1878a38bd9517d\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"5e1878a38bd9517d\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677286667393826816\",\n" +
        "      \"pricefloor\": 12,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"5b939f6fe58e59da\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"5b939f6fe58e59da\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677286913683357696\",\n" +
        "      \"pricefloor\": 14,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"e10875aac15d04f5\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"e10875aac15d04f5\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677287025268621312\",\n" +
        "      \"pricefloor\": 17,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"a5229c70a0bfe5b1\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"a5229c70a0bfe5b1\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677287117690109952\",\n" +
        "      \"pricefloor\": 18,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"29c427287e557d1c\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"29c427287e557d1c\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1681320933031280640\",\n" +
        "      \"pricefloor\": 21,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"c72f90dd45f9c547\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"c72f90dd45f9c547\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1681321064254275584\",\n" +
        "      \"pricefloor\": 29,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"5a20c2f24cba1a3a\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"5a20c2f24cba1a3a\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1681321184307838976\",\n" +
        "      \"pricefloor\": 33,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"c72f90dd45f9c547\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"c72f90dd45f9c547\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1677286792119844864\",\n" +
        "      \"pricefloor\": 13,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"45d805d79bfeb32d\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"45d805d79bfeb32d\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1669345834426302464\",\n" +
        "      \"pricefloor\": 100,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"9af71571234cadae\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"9af71571234cadae\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1669345441134804992\",\n" +
        "      \"pricefloor\": 25,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"9b42a9427eb8e809\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"9b42a9427eb8e809\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1659214185172697088\",\n" +
        "      \"pricefloor\": 9,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"baa50d8030914efc\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"baa50d8030914efc\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1659214218232201216\",\n" +
        "      \"pricefloor\": 8,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"300d1eb36215482e\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"300d1eb36215482e\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"applovin\",\n" +
        "      \"label\": \"applovin\",\n" +
        "      \"uid\": \"1659214267821457408\",\n" +
        "      \"pricefloor\": 6,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"4a3b197068b9f139\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"4a3b197068b9f139\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"bigoads\",\n" +
        "      \"label\": \"bigoads\",\n" +
        "      \"uid\": \"1686021630737907712\",\n" +
        "      \"pricefloor\": 0.01,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"10032853-10119832\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"10032853-10119832\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"inmobi\",\n" +
        "      \"label\": \"inmobi\",\n" +
        "      \"uid\": \"1701607552585957376\",\n" +
        "      \"pricefloor\": 0.01,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"1692495202809\",\n" +
        "      \"placement_id\": \"1692495202809\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"gam\",\n" +
        "      \"label\": \"gam\",\n" +
        "      \"uid\": \"1731667697613148160\",\n" +
        "      \"pricefloor\": 0.4,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"/22897248656/Appodeal/Interstitials/0.4_USD\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1669345004000247808\",\n" +
        "      \"pricefloor\": 140,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_1400\",\n" +
        "      \"placement_id\": \"bidon_inter_1400\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1669345149089611776\",\n" +
        "      \"pricefloor\": 80,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_800\",\n" +
        "      \"placement_id\": \"bidon_inter_800\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1633841803553800192\",\n" +
        "      \"pricefloor\": 9,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_9\",\n" +
        "      \"placement_id\": \"bidon_inter_9\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212366346321920\",\n" +
        "      \"pricefloor\": 1,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_1\",\n" +
        "      \"placement_id\": \"bidon_inter_1\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212481492549632\",\n" +
        "      \"pricefloor\": 2,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_2\",\n" +
        "      \"placement_id\": \"bidon_inter_2\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212731515011072\",\n" +
        "      \"pricefloor\": 6,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_6\",\n" +
        "      \"placement_id\": \"bidon_inter_6\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212791078322176\",\n" +
        "      \"pricefloor\": 5,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_5\",\n" +
        "      \"placement_id\": \"bidon_inter_5\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212858447233024\",\n" +
        "      \"pricefloor\": 4,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_4\",\n" +
        "      \"placement_id\": \"bidon_inter_4\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659212935119110144\",\n" +
        "      \"pricefloor\": 3,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_3\",\n" +
        "      \"placement_id\": \"bidon_inter_3\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659213149049585664\",\n" +
        "      \"pricefloor\": 10,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \" bidon_inter_10\",\n" +
        "      \"placement_id\": \" bidon_inter_10\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1633841922508455936\",\n" +
        "      \"pricefloor\": 13,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_13\",\n" +
        "      \"placement_id\": \"bidon_inter_13\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1633842029886832640\",\n" +
        "      \"pricefloor\": 43,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_43\",\n" +
        "      \"placement_id\": \"bidon_inter_43\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659213203386793984\",\n" +
        "      \"pricefloor\": 8,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \" bidon_inter_8\",\n" +
        "      \"placement_id\": \" bidon_inter_8\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1659213384702361600\",\n" +
        "      \"pricefloor\": 7,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_7\",\n" +
        "      \"placement_id\": \"bidon_inter_7\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1687094776463228928\",\n" +
        "      \"pricefloor\": 28,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_280\",\n" +
        "      \"placement_id\": \"bidon_inter_280\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1687094915210805248\",\n" +
        "      \"pricefloor\": 23,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"bidon_inter_230\",\n" +
        "      \"placement_id\": \"bidon_inter_230\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"unityads\",\n" +
        "      \"label\": \"unityads\",\n" +
        "      \"uid\": \"1677287272065662976\",\n" +
        "      \"pricefloor\": 15,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"inter_new_15\",\n" +
        "      \"placement_id\": \"inter_new_15\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"dtexchange\",\n" +
        "      \"label\": \"dtexchange\",\n" +
        "      \"uid\": \"1681322042483408896\",\n" +
        "      \"pricefloor\": 22,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"1428701\",\n" +
        "      \"placement_id\": \"1428701\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1669346307724148736\",\n" +
        "      \"pricefloor\": 6,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/8924924287\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1677285532993978368\",\n" +
        "      \"pricefloor\": 8,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/9176368044\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1677285864872476672\",\n" +
        "      \"pricefloor\": 13,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/7935438563\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1677286017318649856\",\n" +
        "      \"pricefloor\": 14,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/7289367462\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1687095169737949184\",\n" +
        "      \"pricefloor\": 300,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/4020377420\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1687095262348181504\",\n" +
        "      \"pricefloor\": 160,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/1518901810\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1687095344271327232\",\n" +
        "      \"pricefloor\": 90,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/1722135694\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"demand_id\": \"admob\",\n" +
        "      \"label\": \"admob\",\n" +
        "      \"uid\": \"1687095428702666752\",\n" +
        "      \"pricefloor\": 55,\n" +
        "      \"bid_type\": \"CPM\",\n" +
        "      \"ad_unit_id\": \"ca-app-pub-7174718190807894/6782890683\",\n" +
        "      \"placement_id\": \"\",\n" +
        "      \"zoned_id\": \"\",\n" +
        "      \"slot_uuid\": \"\",\n" +
        "      \"slot_id\": \"\"\n" +
        "    }\n" +
        "  ],\n" +
        "  \"segment\": {\n" +
        "    \"demand_id\": \"\",\n" +
        "    \"uid\": \"\"\n" +
        "  },\n" +
        "  \"token\": \"{}\",\n" +
        "  \"auction_pricefloor\": 0.001,\n" +
        "  \"auction_timeout\": 30000,\n" +
        "  \"auction_id\": \"d1f3699d-7bf4-4a8b-897e-31990ec502d7\"\n" +
        "}"
