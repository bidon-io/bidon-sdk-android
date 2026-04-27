package org.bidon.gma.impl

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.gma.asBidonError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class GmaInterstitialImpl(
    private val getFullScreenEventCallback: GetFullScreenEventCallbackUseCase = GetFullScreenEventCallbackUseCase(),
    private val obtainAdAuctionParams: GetAdAuctionParamsUseCase = GetAdAuctionParamsUseCase(),
) : AdSource.Interstitial<GmaFullscreenAdAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var interstitialAd: InterstitialAd? = null

    override val isAdReadyToShow: Boolean
        get() = interstitialAd != null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return obtainAdAuctionParams(auctionParamsScope, demandAd.adType)
    }

    override fun load(adParams: GmaFullscreenAdAuctionParams) {
        logInfo(TAG, "Starting with $adParams")
        val adUnitId = when (adParams) {
            is GmaFullscreenAdAuctionParams.Network -> adParams.adUnitId
        } ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "adUnitId")
                )
            )
            return
        }
        val adRequest = AdRequest.Builder(adUnitId).build()
        InterstitialAd.load(adRequest, object : AdLoadCallback<InterstitialAd> {
            override fun onAdFailedToLoad(error: LoadAdError) {
                logInfo(TAG, "onAdFailedToLoad: $error")
                emitEvent(AdEvent.LoadFailed(error.asBidonError()))
            }

            override fun onAdLoaded(ad: InterstitialAd) {
                logInfo(TAG, "onAdLoaded: $ad")
                this@GmaInterstitialImpl.interstitialAd = ad
                ad.adEventCallback = getFullScreenEventCallback.createInterstitialCallback(
                    adEventFlow = this@GmaInterstitialImpl,
                    getAd = { getAd() },
                    onClosed = { this@GmaInterstitialImpl.interstitialAd = null }
                )
                getAd()?.let { emitEvent(AdEvent.Fill(it)) }
            }
        })
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        if (interstitialAd == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        } else {
            interstitialAd?.show(activity)
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        interstitialAd = null
    }
}

private const val TAG = "GmaInterstitial"
