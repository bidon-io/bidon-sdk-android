package org.bidon.gma.impl

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.gma.asBidonError
import org.bidon.gma.ext.asBidonAdValue
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
    private val getAdRequest: GetAdRequestUseCase = GetAdRequestUseCase(),
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

        InterstitialAd.load(
            getAdRequest(adUnitId),
            object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    logInfo(TAG, "onAdLoaded: $this")
                    interstitialAd = ad
                    adParams.activity.runOnUiThread {
                        ad.adEventCallback = object : InterstitialAdEventCallback {
                            override fun onAdImpression() {
                                logInfo(TAG, "onAdImpression: $this")
                                getAd()?.let { emitEvent(AdEvent.Shown(it)) }
                            }

                            override fun onAdClicked() {
                                logInfo(TAG, "onAdClicked: $this")
                                getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
                            }

                            override fun onAdDismissedFullScreenContent() {
                                logInfo(TAG, "onAdDismissedFullScreenContent: $this")
                                getAd()?.let { emitEvent(AdEvent.Closed(it)) }
                                interstitialAd = null
                            }

                            override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                                logInfo(TAG, "onAdFailedToShowFullScreenContent: $this")
                                emitEvent(AdEvent.ShowFailed(error.asBidonError()))
                            }

                            override fun onAdShowedFullScreenContent() {
                                // no-op
                            }

                            override fun onAdPaid(adValue: AdValue) {
                                getAd()?.let {
                                    emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue()))
                                }
                            }
                        }
                        getAd()?.let { emitEvent(AdEvent.Fill(it)) }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    logInfo(TAG, "onAdFailedToLoad: $error. $this")
                    emitEvent(AdEvent.LoadFailed(error.asBidonError()))
                }
            }
        )
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
        interstitialAd?.adEventCallback = null
        interstitialAd = null
    }
}

private const val TAG = "GmaInterstitial"
