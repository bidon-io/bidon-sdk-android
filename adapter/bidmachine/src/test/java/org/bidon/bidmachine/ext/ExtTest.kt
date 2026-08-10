package org.bidon.bidmachine.ext

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.bidon.bidmachine.BMAuctionResult
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.json.jsonObject
import org.junit.Test

class ExtTest {

    @Test
    fun `relay ml floor predictions into ad unit ext`() {
        val adUnit = createAdUnit(serverExt = jsonObject { "payload" hasValue "payload123" }.toString())

        adUnit.addMlFloorPredictions(bidWith("ml_floor_predictions" to Predictions))

        assertThat(adUnit.extra?.getString("ml_floor_predictions")).isEqualTo(Predictions)
        assertThat(adUnit.extra?.getString("payload")).isEqualTo("payload123")
    }

    @Test
    fun `keep ad unit ext untouched when bid carries no predictions`() {
        val adUnit = createAdUnit(serverExt = jsonObject { "payload" hasValue "payload123" }.toString())

        adUnit.addMlFloorPredictions(bidWith("other_param" to "value123"))

        assertThat(adUnit.extra?.has("ml_floor_predictions")).isFalse()
        assertThat(adUnit.extra?.getString("payload")).isEqualTo("payload123")
    }

    @Test
    fun `relay predictions into the ad unit already captured by the stats row`() {
        val adUnit = createAdUnit(serverExt = jsonObject { "payload" hasValue "payload123" }.toString())
        val statisticsCollector = StatisticsCollectorImpl().apply { markFillStarted(adUnit, 2.75) }

        adUnit.addMlFloorPredictions(bidWith("ml_floor_predictions" to Predictions))

        assertThat(statisticsCollector.getStats().adUnit?.extra?.getString("ml_floor_predictions"))
            .isEqualTo(Predictions)
    }

    @Test
    fun `skip predictions when ad unit came without ext`() {
        val adUnit = createAdUnit(serverExt = null)

        adUnit.addMlFloorPredictions(bidWith("ml_floor_predictions" to Predictions))

        assertThat(adUnit.extra).isNull()
    }

    private fun bidWith(vararg customParams: Pair<String, String>) = mockk<BMAuctionResult> {
        every { this@mockk.customParams } returns mapOf(*customParams)
    }

    private fun createAdUnit(serverExt: String?) = AdUnit(
        demandId = "bidmachine",
        label = "label123",
        pricefloor = 2.75,
        uid = "uid123",
        bidType = BidType.RTB,
        timeout = 5000,
        ext = serverExt,
    )
}

private const val Predictions = """[{"ecpm":1.2,"probability":0.8}]"""
