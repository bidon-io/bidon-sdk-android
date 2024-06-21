package org.bidon.sdk.config.models.data.models.auction

import com.google.common.truth.Truth.assertThat
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResponseParser
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.JsonParsers
import org.bidon.sdk.utils.json.jsonObject
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 08/02/2023.
 */
internal class AuctionResponseParserTest {

    private val testee by lazy { JsonParsers }

    @Test
    fun `it should parse auction response`() {
        val result = testee.parseOrNull<AuctionResponse>(responseJsonStr)

        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(expectedModel)
    }

    private val expectedModel = AuctionResponse(
        adUnits = listOf(
            AdUnit(
                demandId = "bidmachine",
                label = "bm_interstitial_cpm",
                uid = "1718930569917632512",
                pricefloor = 10000.0,
                timeout = 5000,
                bidType = BidType.RTB,
                ext = null,
            ),
            AdUnit(
                demandId = "admob",
                label = "admob_android_interstitial_26",
                pricefloor = 26.0,
                uid = "1687095657711665152",
                timeout = 5000,
                bidType = BidType.CPM,
                ext = jsonObject { "ad_unit_id" hasValue "ca-app-pub-7174718190807894/4883431752" }.toString(),
            )
        ),
        pricefloor = 0.01,
        auctionId = "54e4c13b-f642-4fc2-88aa-527181061390",
        auctionConfigurationId = 83,
        auctionConfigurationUid = "1801267324553007104",
        externalWinNotificationsEnabled = false,
        auctionTimeout = 15000
    )

    private val responseJsonStr = """
                  {
                  	"auction_configuration_id": 83,
                  	"auction_configuration_uid": "1801267324553007104",
                  	"external_win_notifications": false,
                  	"ad_units": [{
                  		"demand_id": "bidmachine",
                  		"uid": "1718930569917632512",
                  		"label": "bm_interstitial_cpm",
                  		"pricefloor": 10000,
                        "timeout": 5000,
                  		"bid_type": "RTB",
                  	}, {
                  		"demand_id": "admob",
                  		"uid": "1687095657711665152",
                  		"label": "admob_android_interstitial_26",
                  		"pricefloor": 26,
                        "timeout": 5000,
                  		"bid_type": "CPM",
                  		"ext": {
                  			"ad_unit_id": "ca-app-pub-7174718190807894/4883431752"
                  		}
                  	}],
                  	"segment": {
                  		"id": "",
                  		"uid": ""
                  	},
                  	"token": "{}",
                  	"auction_pricefloor": 0.01,
                  	"auction_timeout": 15000,
                  	"auction_id": "54e4c13b-f642-4fc2-88aa-527181061390"
                  }
    """.trimIndent()

    @Test
    fun `it should parse auction_configuration_uid as String`() {
        val responseJsonStr = """
        {
        	"auction_configuration_id": 83,
        	"auction_configuration_uid": "1801267324553007104",
        	"external_win_notifications": false,
        	"ad_units": [{
        		"demand_id": "bidmachine",
        		"uid": "1718930569917632512",
        		"label": "bm_interstitial_cpm",
        		"pricefloor": 10000,
        		"bid_type": "CPM",
        		"ext": {}
        	}, {
        		"demand_id": "admob",
        		"uid": "1687095657711665152",
        		"label": "admob_android_interstitial_26",
        		"pricefloor": 26,
        		"bid_type": "CPM",
        		"ext": {
        			"ad_unit_id": "ca-app-pub-7174718190807894/4883431752"
        		}
        	}, {
        		"demand_id": "dtexchange",
        		"uid": "1659215744401014784",
        		"label": "dt_android_inter_25",
        		"pricefloor": 25,
        		"bid_type": "CPM",
        		"ext": {
        			"spot_id": "1311439"
        		}
        	}, {
        		"demand_id": "admob",
        		"uid": "1677285864872476672",
        		"label": "mergeblocks android admob inter 13",
        		"pricefloor": 13,
        		"bid_type": "CPM",
        		"ext": {
        			"ad_unit_id": "ca-app-pub-7174718190807894/7935438563"
        		}
        	}, {
        		"demand_id": "dtexchange",
        		"uid": "1633841366150807552",
        		"label": "dt_android_inter_12",
        		"pricefloor": 12,
        		"bid_type": "CPM",
        		"ext": {
        			"spot_id": "1187218"
        		}
        	}, {
        		"demand_id": "admob",
        		"uid": "1669346307724148736",
        		"label": "admob_android_interstitial_6",
        		"pricefloor": 6,
        		"bid_type": "CPM",
        		"ext": {
        			"ad_unit_id": "ca-app-pub-7174718190807894/8924924287"
        		}
        	}, {
        		"demand_id": "admob",
        		"uid": "1677285114813480960",
        		"label": "mergeblocks android admob inter 3",
        		"pricefloor": 3,
        		"bid_type": "CPM",
        		"ext": {
        			"ad_unit_id": "ca-app-pub-7174718190807894/8701725322"
        		}
        	}, {
        		"demand_id": "dtexchange",
        		"uid": "1659215523843538944",
        		"label": "dt_android_inter_2",
        		"pricefloor": 2,
        		"bid_type": "CPM",
        		"ext": {
        			"spot_id": "1311431"
        		}
        	}, {
        		"demand_id": "vungle",
        		"uid": "1687107176709095424",
        		"label": "vungle_bidding_android_inter",
        		"pricefloor": 1.48127613,
        		"bid_type": "RTB",
        		"ext": {
        			"payload": "{\"version\":2,\"adunit\":\"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\",\"impression\":[\"https://events.ads.vungle.com/api/impression?ext=ZDtEFzUShwTNwhTlwxy35HoPq9-Q1FqKWwXQEPTfylHyFoE55TVcZ6M6tyhZwdSEKu9kwkfOZhdww06ZU__K6qr47fdkDnW_yGDi0tDb86qPMhLENr6mSliPz7qHo8w4vcvoGiNW3ctJsuiiJCu5QsEV5b_eRa4V5tYqsqhdbOI4Qv67CdyqdkhZRwY9DDiLALEsWpj4MS3urMLvI6Wp6v6v3jHhN9nd0K7ZGHhYgvZA5-I4HIj5K1rg2sb7CA1z7WN26BKyrwTFi1WNlPeo-KsUc6hNLmV9NjEU6Lm4K7DkKHfMNRSaMtTYTDf1o49wkUD_bxW6IOTmuvrKbyAYUKjlJQlmVd8spupohXic1qadsKk-ITFDW1XCUYpdqyUPwZbyiI6PyVQX9KY4-Xtsaujh-bLJ8cJI0i9EaR7mK0X12oLpbDAKgf9JItgRD64wGzaORtddEIUA9v-OECrkxRLSEiEZFLFF6Cecp6vN0aOlX0rGpuAmSHXfBDdgF-twWocLS37QnGtDG6CbbZU5rxlzHOSqYnC9U4tZ9kcJsGMejmcI_87wl8I0OZ32QBdIXy7w9fZ8yTlbSDV88fKCDy2amm7NQ52X0-IF-XFT7lKQpXOZKriyW29I-B2NXoAG5Iqxpc5joWm1yr_bahHWCF0pVsOxfjm6mntsBzUEbL9WtjQfyRnK70FaTeGPkDUT-GMVURN2Jk71R6VMK1ZlijHDWvikHKkPGRim7v4p8UKzcqJQWXmqi5vMoqNeq_pgzrk69L51qFgvfT74HukKErJSHyrQDY726qksNwZBkIQ0LsxB-X0S3eUvvEIEEbe-4g-gMsxK14otyWlg1bZTT7LFbJLuaPxIO8QswdNXT_dX7N_y149IFilTQ_D5pgJVG3iBlJSQBsIfm89-IqFyYQh0T0hAsodbKleZHsRnechpi1lXq9yXlwFVy-kMFqK2o5FO8O8F1eg-N9F1i2wBiPUjdLwtSCEcbUk4w7udp7MQHK7OgiP13jqWjTrQrOgNmWfHxkpvCiZD0Q3Fq05ekps0ODzcKRXHtVGUY6wKP9g6whceW9JTGvq2eRd-6OvmMOgrhogi4Z3NTiC22Nj3Wbh13X-VVahvgVsf4FcBuXkcyG1cWFcfakB5n1r3ZhpbVz_7KhNW7C63fsJaTeihjFDhK9TKmExo_cC_XhLjWw==\"]}",
        			"placement_id": "INTER_TEST-1131362"
        		}
        	}],
        	"segment": {
        		"id": "",
        		"uid": ""
        	},
        	"token": "{}",
        	"auction_pricefloor": 0.001,
        	"auction_timeout": 15000,
        	"auction_id": "54e4c13b-f642-4fc2-88aa-527181061390"
        }
        """.trimIndent()
        val res = AuctionResponseParser().parseOrNull(responseJsonStr)
        assertThat(res?.auctionConfigurationUid).isEqualTo("1801267324553007104")
    }

    @Test
    fun `it should parse auction_configuration_id as Long`() {
        val responseJsonStr = """
        {
        	"auction_configuration_id": 83,
        	"auction_configuration_uid": "1801267324553007104",
        	"external_win_notifications": false,
        	"ad_units": [{
        		"demand_id": "bidmachine",
        		"uid": "1718930569917632512",
        		"label": "bm_interstitial_cpm",
        		"pricefloor": 10000,
              "timeout": 5000,
        		"bid_type": "RTB",
        	}, {
        		"demand_id": "admob",
        		"uid": "1687095657711665152",
        		"label": "admob_android_interstitial_26",
        		"pricefloor": 26,
              "timeout": 5000,
        		"bid_type": "CPM",
        		"ext": {
        			"ad_unit_id": "ca-app-pub-7174718190807894/4883431752"
        		}
        	}],
        	"segment": {
        		"id": "",
        		"uid": ""
        	},
        	"token": "{}",
        	"auction_pricefloor": 0.01,
        	"auction_timeout": 15000,
        	"auction_id": "54e4c13b-f642-4fc2-88aa-527181061390"
        }
        """.trimIndent()
        val res = AuctionResponseParser().parseOrNull(responseJsonStr)
        assertThat(res?.auctionConfigurationId).isEqualTo(83)
    }
}