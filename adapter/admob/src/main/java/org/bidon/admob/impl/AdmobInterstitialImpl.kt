package org.bidon.admob.impl

import android.app.Activity
import android.content.Context
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdFormat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.admob.AdmobFullscreenAdAuctionParams
import org.bidon.admob.DefaultTokenTimeoutMs
import org.bidon.admob.asBidonError
import org.bidon.admob.ext.asBidonAdValue
import org.bidon.admob.ext.asBundle
import org.bidon.admob.ext.bindBiddingParams
import org.bidon.admob.ext.bindFillParams
import org.bidon.admob.ext.getAdAuctionParams
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal class AdmobInterstitialImpl :
    AdSource.Interstitial<AdmobFullscreenAdAuctionParams>,
    Mode.Bidding,
    Mode.Network,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var param: AdmobFullscreenAdAuctionParams? = null
    private var interstitialAd: InterstitialAd? = null
    private var isBiddingMode: Boolean = false

    override val isAdReadyToShow: Boolean
        get() = interstitialAd != null

    override suspend fun getToken(context: Context): String? {
        isBiddingMode = true
        val adRequest = AdRequest.Builder()
            .bindBiddingParams()
            .build()

        return withTimeoutOrNull(DefaultTokenTimeoutMs) {
            suspendCoroutine { continuation ->
                QueryInfo.generate(
                    context,
                    AdFormat.INTERSTITIAL,
                    adRequest,
                    object : QueryInfoGenerationCallback() {
                        override fun onSuccess(queryInfo: QueryInfo) {
                            continuation.resume(queryInfo.query)
                        }

                        override fun onFailure(errorMessage: String) {
                            continuation.resumeWithException(Exception(errorMessage))
                        }
                    }
                )
            }
        }
    }

    override fun obtainAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope.getAdAuctionParams(isBiddingMode)
    }

    override fun load(adParams: AdmobFullscreenAdAuctionParams) {
        val adRequest = when (adParams) {
            is AdmobFullscreenAdAuctionParams.Bidding -> {
                AdRequest.Builder()
                    .bindFillParams(adParams.payload, adParams.adUnitId)
                    .build()
            }

            is AdmobFullscreenAdAuctionParams.Network -> {
                AdRequest.Builder()
                    .addNetworkExtrasBundle(AdMobAdapter::class.java, BidonSdk.regulation.asBundle())
                    .build()
            }
        }
        param = adParams
        val requestListener = object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                logError(TAG, "onAdFailedToLoad: $loadAdError. $this", loadAdError.asBidonError())
                emitEvent(AdEvent.LoadFailed(loadAdError.asBidonError()))
            }

            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                logInfo(TAG, "onAdLoaded: $this")
                this@AdmobInterstitialImpl.interstitialAd = interstitialAd
                interstitialAd.onPaidEventListener = OnPaidEventListener { adValue ->
                    emitEvent(
                        AdEvent.PaidRevenue(
                            ad = Ad(
                                demandAd = demandAd,
                                ecpm = param?.price ?: 0.0,
                                demandAdObject = interstitialAd,
                                networkName = demandId.demandId,
                                dsp = null,
                                roundId = roundId,
                                currencyCode = "USD",
                                auctionId = auctionId,
                                adUnitId = param?.adUnitId
                            ),
                            adValue = adValue.asBidonAdValue()
                        )
                    )
                }
                interstitialAd.fullScreenContentCallback =
                    object : FullScreenContentCallback() {
                        override fun onAdClicked() {
                            logInfo(TAG, "onAdClicked: $this")
                            emitEvent(AdEvent.Clicked(interstitialAd.asAd()))
                        }

                        override fun onAdDismissedFullScreenContent() {
                            logInfo(TAG, "onAdDismissedFullScreenContent: $this")
                            emitEvent(AdEvent.Closed(interstitialAd.asAd()))
                        }

                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            logError(TAG, "onAdFailedToShowFullScreenContent: $this", error.asBidonError())
                            emitEvent(AdEvent.ShowFailed(error.asBidonError()))
                        }

                        override fun onAdImpression() {
                            logInfo(TAG, "onAdShown: $this")
                            emitEvent(AdEvent.Shown(interstitialAd.asAd()))
                        }

                        override fun onAdShowedFullScreenContent() {}
                    }
                emitEvent(AdEvent.Fill(requireNotNull(interstitialAd.asAd())))
            }
        }
        val adUnitId = when (adParams) {
            is AdmobFullscreenAdAuctionParams.Bidding -> adParams.unitId
            is AdmobFullscreenAdAuctionParams.Network -> adParams.adUnitId
        }
        InterstitialAd.load(adParams.context, adUnitId, adRequest, requestListener)
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        if (interstitialAd == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.FullscreenAdNotReady))
        } else {
            interstitialAd?.show(activity)
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        interstitialAd?.onPaidEventListener = null
        interstitialAd?.fullScreenContentCallback = null
        interstitialAd = null
        param = null
    }

    private fun InterstitialAd.asAd(): Ad {
        return Ad(
            demandAd = demandAd,
            ecpm = param?.price ?: 0.0,
            demandAdObject = this,
            networkName = demandId.demandId,
            dsp = null,
            roundId = roundId,
            currencyCode = AdValue.USD,
            auctionId = auctionId,
            adUnitId = param?.adUnitId
        )
    }
}

private const val TAG = "AdmobInterstitial"
