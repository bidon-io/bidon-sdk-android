package org.bidon.gma.impl

import com.google.android.libraries.ads.mobile.sdk.common.FullScreenAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import org.bidon.gma.asBidonError
import org.bidon.gma.ext.GmaAdValue
import org.bidon.gma.ext.asBidonAdValue
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo

internal class GetFullScreenEventCallbackUseCase {

    fun createInterstitialCallback(
        adEventFlow: AdEventFlow,
        getAd: () -> Ad?,
        onClosed: () -> Unit
    ): InterstitialAdEventCallback {
        return object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                // No-op: use onAdImpression for Shown event
            }

            override fun onAdImpression() {
                logInfo(TAG, "onAdImpression (interstitial)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Shown(it)) }
            }

            override fun onAdClicked() {
                logInfo(TAG, "onAdClicked (interstitial)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Clicked(it)) }
            }

            override fun onAdDismissedFullScreenContent() {
                logInfo(TAG, "onAdDismissedFullScreenContent (interstitial)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Closed(it)) }
                onClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: FullScreenAdError) {
                logError(TAG, "onAdFailedToShowFullScreenContent (interstitial)", error.asBidonError())
                adEventFlow.emitEvent(AdEvent.ShowFailed(error.asBidonError()))
            }

            override fun onAdPaid(adValue: GmaAdValue) {
                logInfo(TAG, "onAdPaid (interstitial): ${adValue.valueMicros}")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue())) }
            }
        }
    }

    fun createRewardedCallback(
        adEventFlow: AdEventFlow,
        getAd: () -> Ad?,
        onClosed: () -> Unit
    ): RewardedAdEventCallback {
        return object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                // No-op: use onAdImpression for Shown event
            }

            override fun onAdImpression() {
                logInfo(TAG, "onAdImpression (rewarded)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Shown(it)) }
            }

            override fun onAdClicked() {
                logInfo(TAG, "onAdClicked (rewarded)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Clicked(it)) }
            }

            override fun onAdDismissedFullScreenContent() {
                logInfo(TAG, "onAdDismissedFullScreenContent (rewarded)")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.Closed(it)) }
                onClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: FullScreenAdError) {
                logError(TAG, "onAdFailedToShowFullScreenContent (rewarded)", error.asBidonError())
                adEventFlow.emitEvent(AdEvent.ShowFailed(error.asBidonError()))
            }

            override fun onAdPaid(adValue: GmaAdValue) {
                logInfo(TAG, "onAdPaid (rewarded): ${adValue.valueMicros}")
                getAd()?.let { adEventFlow.emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue())) }
            }
        }
    }
}

private const val TAG = "GmaFullScreenEventCallback"
