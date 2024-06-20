package org.bidon.sdk.stats.models

import org.bidon.sdk.config.models.json_scheme_utils.assertEquals
import org.bidon.sdk.config.models.json_scheme_utils.expectedJsonStructure
import org.bidon.sdk.utils.json.jsonArray
import org.bidon.sdk.utils.json.jsonObject
import org.bidon.sdk.utils.serializer.serialize
import org.junit.Ignore
import org.junit.Test

/**
 * Created by Bidon Team on 24/02/2023.
 */
class StatsRequestBodySerializerTest {

    @Ignore
    @Test
    fun `it should serialize stat request`() {
        val json = StatsRequestBody(
            auctionId = "id123",
            auctionConfigurationId = 4,
            auctionConfigurationUid = "4",
            auctionPricefloor = 1.0,
            adUnits = listOf(
                StatsAdUnit(
                    demandId = "d345",
                    status = "WIN",
                    price = 1.2,
                    tokenStartTs = 2,
                    tokenFinishTs = 3,
                    bidType = BidType.CPM.code,
                    fillFinishTs = 3,
                    fillStartTs = 4,
                    adUnitUid = "123",
                    adUnitLabel = "label124",
                ),
                StatsAdUnit(
                    demandId = "d6",
                    status = "NO_FILL",
                    price = null,
                    fillFinishTs = null,
                    fillStartTs = null,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    adUnitLabel = "label123",
                    adUnitUid = "123",
                ),
                StatsAdUnit(
                    demandId = "d011",
                    price = 1.0,
                    status = "LOSE",
                    fillFinishTs = 6,
                    fillStartTs = 5,
                    bidType = BidType.RTB.code,
                    adUnitUid = "123",
                    adUnitLabel = "label123",
                    tokenStartTs = 678L,
                    tokenFinishTs = 679L,
                )
            ),
            result = ResultBody(
                status = "SUCCESS",
                winnerDemandId = "d345",
                bidType = BidType.CPM.code,
                price = 1.2,
                winnerAdUnitUid = "123",
                winnerAdUnitLabel = "label124",
                auctionStartTs = 1000,
                auctionFinishTs = 1300,
                banner = null,
                rewarded = null,
                interstitial = null,
            ),
        ).serialize()
        println(json)
        json.assertEquals(
            expectedJsonStructure {
                "result" hasJson expectedJsonStructure {
                    "status" hasValue "SUCCESS"
                    "winner_id" hasValue "admob"
                    "ecpm" hasValue 0.123
                    "ad_unit_id" hasValue "id123"
                    "auction_start_ts" hasValue 1000
                    "auction_finish_ts" hasValue 1300
                    "bid_type" hasValue "CPM"
                }
                "auction_id" hasValue "id123"
                "rounds" hasArray jsonArray {
                    val list = listOf(
                        jsonObject {
                            "winner_ecpm" hasValue 234.1
                            "winner_id" hasValue "asd"
                            "id" hasValue "id13"
                            "pricefloor" hasValue 34.2
                            "biddings" hasValue jsonArray {}
                            "demands" hasValue jsonArray {
                                putValues(
                                    listOf(
                                        jsonObject {
                                            "ad_unit_id" hasValue "asd223"
                                            "bid_finish_ts" hasValue 1
                                            "ecpm" hasValue 1.2
                                            "fill_start_ts" hasValue 4
                                            "fill_finish_ts" hasValue 3
                                            "token_start_ts" hasValue 678L
                                            "token_finish_ts" hasValue 679L
                                            "id" hasValue "d345"
                                            "bid_start_ts" hasValue 2
                                            "status" hasValue "code"
                                        },
                                        jsonObject {
                                            "id" hasValue "d6"
                                            "status" hasValue "code2"
                                        }
                                    )
                                )
                            }
                        },
                        jsonObject {
                            "biddings" hasValue jsonArray {
//                                jsonObject {
//                                    // fixme cannot check internal jsonObject
//                                    "bid_start_ts" hasValue 2
//                                    "bid_finish_ts" hasValue 3
//                                    "fill_start_ts" hasValue 5
//                                    "fill_finish_ts" hasValue 6
//                                    "ecpm" hasValue 1.0
//                                    "id" hasValue "d001"
//                                    "status" hasValue "code3"
//                                }
                            }
                            "id" hasValue "id43"
                            "demands" hasValue jsonArray { }
                            "pricefloor" hasValue 34.2
                        }
                    )
                    putValues(list)
                }
            }
        )
    }

    @Ignore
    @Test
    fun `test Bidding serialize`() {
        val actual = StatsAdUnit(
            demandId = "d011",
            status = "code3",
            price = 1.0,
            fillFinishTs = 6,
            fillStartTs = 5,
            bidType = BidType.RTB.code,
            adUnitLabel = "label123",
            adUnitUid = "123",
            tokenStartTs = 678L,
            tokenFinishTs = 679L,
        ).serialize()

        actual.assertEquals(
            expectedJsonStructure {
                "id" hasValue "d011"
                "status" hasValue "code3"
                "ecpm" hasValue 1.0
                "bid_type" hasValue "RTB"
                "bid_start_ts" hasValue 2
                "bid_finish_ts" hasValue 3
                "fill_start_ts" hasValue 5
                "fill_finish_ts" hasValue 6
                "token_start_ts" hasValue 678L
                "token_finish_ts" hasValue 679L
            }
        )
    }
}
