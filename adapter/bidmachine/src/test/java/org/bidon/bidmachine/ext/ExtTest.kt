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
    fun `relay all bid response custom params into ad unit ext`() {
        val adUnit = createAdUnit(serverExt = serverExt)

        adUnit.addCustomParams(
            bidWith(
                "ml_floor_predictions" to Predictions,
                "some_param" to "value123",
            )
        )

        assertThat(adUnit.extra?.getString("ml_floor_predictions")).isEqualTo(Predictions)
        assertThat(adUnit.extra?.getString("some_param")).isEqualTo("value123")
        assertThat(adUnit.extra?.getString("payload")).isEqualTo("payload123")
    }

    @Test
    fun `keep ad unit ext untouched when bid carries no custom params`() {
        val adUnit = createAdUnit(serverExt = serverExt)

        adUnit.addCustomParams(bidWith())

        assertThat(adUnit.extra?.length()).isEqualTo(1)
        assertThat(adUnit.extra?.getString("payload")).isEqualTo("payload123")
    }

    @Test
    fun `relay custom params into the ad unit already captured by the stats row`() {
        val adUnit = createAdUnit(serverExt = serverExt)
        val statisticsCollector = StatisticsCollectorImpl().apply { markFillStarted(adUnit, 2.75) }

        adUnit.addCustomParams(bidWith("ml_floor_predictions" to Predictions))

        assertThat(statisticsCollector.getStats().adUnit?.extra?.getString("ml_floor_predictions"))
            .isEqualTo(Predictions)
    }

    @Test
    fun `skip custom params when ad unit came without ext`() {
        val adUnit = createAdUnit(serverExt = null)

        adUnit.addCustomParams(bidWith("ml_floor_predictions" to Predictions))

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

private val serverExt = jsonObject { "payload" hasValue "payload123" }.toString()
private const val Predictions = """[{"ecpm":1.2,"probability":0.8}]"""
