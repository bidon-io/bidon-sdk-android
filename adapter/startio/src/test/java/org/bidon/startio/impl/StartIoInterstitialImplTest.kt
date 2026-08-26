package org.bidon.startio.impl

import android.app.Activity
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import com.startapp.sdk.adsbase.model.AdPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.bidon.sdk.auction.models.AdUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Test

class StartIoInterstitialImplTest {

    private val context = mockk<Context>(relaxed = true)
    private val activity = mockk<Activity>(relaxed = true)
    private val testee = StartIoInterstitialImpl()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isAdReadyToShow should return false when no ad loaded`() {
        // Initially no ad is loaded, so should return false
        assertThat(testee.isAdReadyToShow).isFalse()
    }

    @Test
    fun `load should request an interstitial with the given payload`() {
        // StartAppAd is mocked out: the real SDK blocks indefinitely in a JVM unit test
        mockkConstructor(StartAppAd::class)
        every {
            anyConstructed<StartAppAd>().loadAd(
                any<StartAppAd.AdMode>(),
                any<AdPreferences>(),
                any<AdEventListener>(),
                any<String>()
            )
        } just Runs
        val extraJson = JSONObject().apply {
            put("payload", "test_payload")
        }
        val adUnit = mockk<AdUnit>(relaxed = true) {
            every { pricefloor } returns 1.0
            every { extra } returns extraJson
        }
        val adParams = StartIoFullscreenAuctionParams(context, adUnit)

        testee.load(adParams)

        verify(exactly = 1) {
            anyConstructed<StartAppAd>().loadAd(
                StartAppAd.AdMode.AUTOMATIC,
                any<AdPreferences>(),
                any<AdEventListener>(),
                "test_payload"
            )
        }
    }

    @Test
    fun `load should handle null payload`() {
        val adUnit = mockk<AdUnit>(relaxed = true) {
            every { pricefloor } returns 1.0
            every { extra } returns null
        }
        val adParams = StartIoFullscreenAuctionParams(context, adUnit)

        // Should handle null payload gracefully
        testee.load(adParams)

        // After loading with null payload, ad should not be ready
        assertThat(testee.isAdReadyToShow).isFalse()
    }

    @Test
    fun `show should handle case when no ad is loaded`() {
        // Should not crash when trying to show without loaded ad
        testee.show(activity)

        // Ad should still not be ready
        assertThat(testee.isAdReadyToShow).isFalse()
    }

    @Test
    fun `destroy should reset ad state`() {
        // Should not crash when destroying
        testee.destroy()

        // After destroy, ad should not be ready
        assertThat(testee.isAdReadyToShow).isFalse()
    }

    @Test
    fun `getAuctionParam should return success result`() {
        val auctionParamsScope = mockk<org.bidon.sdk.adapter.AdAuctionParamSource>(relaxed = true)

        val result = testee.getAuctionParam(auctionParamsScope)

        assertThat(result).isNotNull()
        assertThat(result.isSuccess).isTrue()
    }
}
