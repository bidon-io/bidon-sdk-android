package org.bidon.unityads.impl

import android.app.Activity
import com.unity3d.ads.InterstitialAd
import com.unity3d.ads.InterstitialShowListener
import com.unity3d.ads.LoadConfiguration
import com.unity3d.ads.ShowConfiguration
import com.unity3d.ads.ShowFinishState
import com.unity3d.ads.UnityAdsError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.unityads.ext.asBidonError

/**
 * Created by Bidon Team on 02/03/2023.
 */
internal class UnityAdsInterstitial :
    AdSource.Interstitial<UnityAdsFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var adUnit: AdUnit? = null
    private var interstitialAd: InterstitialAd? = null

    override var isAdReadyToShow: Boolean = false

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            UnityAdsFullscreenAuctionParams(
                adUnit = adUnit
            )
        }
    }

    override fun load(adParams: UnityAdsFullscreenAuctionParams) {
        logInfo(TAG, "Starting with $adParams: $this")
        val placementId = adParams.placementId ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "placementId")
                )
            )
            return
        }
        adUnit = adParams.adUnit

        val loadConfig = LoadConfiguration.Builder(placementId).build()
        InterstitialAd.load(loadConfig) { loadedAd, error ->
            if (loadedAd != null) {
                logInfo(TAG, "onUnityAdsAdLoaded: $this")
                interstitialAd = loadedAd
                isAdReadyToShow = true
                getAd()?.let {
                    emitEvent(AdEvent.Fill(it))
                }
            } else {
                logInfo(TAG, "onUnityAdsFailedToLoad: placementId=$placementId, error=${error?.message}")
                emitEvent(AdEvent.LoadFailed(error.asBidonError()))
            }
        }
    }

    override fun show(activity: Activity) {
        val ad = interstitialAd ?: return
        val showConfig = ShowConfiguration.Builder().build()
        val showListener = object : InterstitialShowListener {
            override fun onFailed(unityAd: InterstitialAd, error: UnityAdsError) {
                logError(
                    tag = TAG,
                    message = "onUnityAdsShowFailure: error=${error.message}",
                    error = error.asBidonError()
                )
                emitEvent(AdEvent.ShowFailed(error.asBidonError()))
            }

            override fun onStarted(unityAd: InterstitialAd) {
                logInfo(TAG, "onUnityAdsShowStart")
                getAd()?.let {
                    emitEvent(AdEvent.Shown(it))
                    emitEvent(
                        AdEvent.PaidRevenue(
                            ad = it,
                            adValue = AdValue(
                                adRevenue = (adUnit?.pricefloor ?: 0.0) / 1000.0,
                                currency = AdValue.USD,
                                precision = Precision.Estimated
                            )
                        )
                    )
                }
            }

            override fun onClicked(unityAd: InterstitialAd) {
                logInfo(TAG, "onUnityAdsShowClick")
                getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
            }

            override fun onCompleted(unityAd: InterstitialAd, state: ShowFinishState) {
                logInfo(TAG, "onUnityAdsShowComplete: state=$state")
                getAd()?.let {
                    emitEvent(AdEvent.Closed(it))
                }
            }
        }
        ad.show(activity, showConfig, showListener)
        isAdReadyToShow = false
    }

    override fun destroy() {
        // do nothing
    }
}

private const val TAG = "UnityAdsInterstitial"