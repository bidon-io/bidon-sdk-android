package org.bidon.demoapp.ui

import android.app.Activity
import android.content.Context
import com.chartboost.heliumsdk.HeliumIlrdObserver
import com.chartboost.heliumsdk.HeliumImpressionData
import com.chartboost.heliumsdk.HeliumSdk
import com.chartboost.heliumsdk.ad.HeliumFullscreenAdListener
import com.chartboost.heliumsdk.ad.HeliumInterstitialAd
import com.chartboost.heliumsdk.domain.AdFormat
import com.chartboost.heliumsdk.domain.AdInteractionListener
import com.chartboost.heliumsdk.domain.ChartboostMediationAdException
import com.chartboost.heliumsdk.domain.PartnerAd
import com.chartboost.heliumsdk.domain.PartnerAdListener
import com.chartboost.heliumsdk.domain.PartnerAdLoadRequest
import com.chartboost.heliumsdk.domain.PreBidRequest
import com.chartboost.mediation.googlebiddingadapter.GoogleBiddingAdapter
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Created by Aleksei Cherniaev on 18/08/2023.
 */
object ChartBoo {
    fun st(coroutineScope: CoroutineScope, activity: Activity) {
        initChartboost(
            context = activity,
            appId = "64d10336ec5b182b9e101000",
            appSignature = "331d6a248a70f9264c32259512f1321eba47c608",
        ) {
            loadInterstitial(coroutineScope, activity)
            return@initChartboost
            coroutineScope.launch {
                val adapter = GoogleBiddingAdapter()
                adapter.setUp(activity).onSuccess {
                    logInfo(TAG, "Chartboost bidding adapter setup success")
                    val map = adapter.fetchBidderInformation(
                        context = activity,
                        request = PreBidRequest(
                            chartboostPlacement = "chartboostTestInterstitial",
                            format = AdFormat.INTERSTITIAL,
                        )
                    )
                    logInfo(TAG, "Chartboost bidding adapter fetchBidderInformation success: $map")
                    adapter.load(activity,
                        PartnerAdLoadRequest(
                            partnerId = adapter.partnerId,
                            format = AdFormat.INTERSTITIAL,
                            chartboostPlacement = "chartboostTestInterstitial",
                            size = null,
                            adm = "",
                            adInteractionListener = object : AdInteractionListener {
                                override fun onClicked(partnerAd: PartnerAd) {
                                    logInfo(TAG, "AdInteractionListener onClicked $partnerAd")
                                }

                                override fun onDismissed(partnerAd: PartnerAd, error: ChartboostMediationAdException?) {
                                    logInfo(TAG, "AdInteractionListener onDismissed $partnerAd")
                                }

                                override fun onExpired(partnerAd: PartnerAd) {
                                    logInfo(TAG, "AdInteractionListener onExpired $partnerAd")
                                }

                                override fun onImpressionTracked(partnerAd: PartnerAd) {
                                    logInfo(TAG, "AdInteractionListener onImpressionTracked $partnerAd")
                                }

                                override fun onRewarded(partnerAd: PartnerAd) {
                                    logInfo(TAG, "AdInteractionListener onRewarded $partnerAd")
                                }
                            },
                            identifier = "125",
                            partnerPlacement = "124",
                            partnerSettings = map,
                        ),
                        object : PartnerAdListener {
                            override fun onPartnerAdClicked(partnerAd: PartnerAd) {
                                logInfo(TAG, "PartnerAdListener onPartnerAdClicked $partnerAd")
                            }

                            override fun onPartnerAdDismissed(partnerAd: PartnerAd, error: ChartboostMediationAdException?) {
                                logInfo(TAG, "PartnerAdListener onPartnerAdDismissed $partnerAd")
                            }

                            override fun onPartnerAdExpired(partnerAd: PartnerAd) {
                                logInfo(TAG, "PartnerAdListener onPartnerAdExpired $partnerAd")
                            }

                            override fun onPartnerAdImpression(partnerAd: PartnerAd) {
                                logInfo(TAG, "PartnerAdListener onPartnerAdImpression $partnerAd")
                            }

                            override fun onPartnerAdRewarded(partnerAd: PartnerAd) {
                                logInfo(TAG, "PartnerAdListener onPartnerAdRewarded $partnerAd")
                            }
                        }
                    ).onSuccess {
                        logInfo(TAG, "Chartboost bidding adapter load success")
                    }.onFailure {
                        logError(TAG, "Chartboost bidding adapter load failed", it)
                    }

                }.onFailure {
                    logError(TAG, "Chartboost bidding adapter setup failed", it)
                }
            }
        }
    }

    private fun loadInterstitial(coroutineScope: CoroutineScope, activity: Activity) {
        logInfo(TAG, "HeliumFullscreenAdListener loadInterstitial")
        HeliumInterstitialAd(activity,
            placementName = "chartboostTestInterstitial",
            heliumFullscreenAdListener = object : HeliumFullscreenAdListener{
                override fun onAdCached(
                    placementName: String,
                    loadId: String,
                    winningBidInfo: Map<String, String>,
                    error: ChartboostMediationAdException?
                ) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdCached $placementName $loadId $winningBidInfo $error")
                }

                override fun onAdClicked(placementName: String) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdClicked $placementName")
                }

                override fun onAdClosed(placementName: String, error: ChartboostMediationAdException?) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdClosed $placementName $error")
                }

                override fun onAdImpressionRecorded(placementName: String) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdImpressionRecorded $placementName")
                }

                override fun onAdRewarded(placementName: String) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdRewarded $placementName")
                }

                override fun onAdShown(placementName: String, error: ChartboostMediationAdException?) {
                    logInfo(TAG, "HeliumFullscreenAdListener onAdShown $placementName $error")
                }

            })
    }

    private fun initChartboost(
        context: Context,
        appId: String,
        appSignature: String,
        onFinished: () -> Unit

    ) {
        MobileAds.disableMediationAdapterInitialization(context)
        HeliumSdk.start(context, appId, appSignature) { error ->
            HeliumSdk.setTestMode(true)
            when (error) {
                null -> {
                    HeliumSdk.subscribeIlrd(object : HeliumIlrdObserver {
                        override fun onImpression(impData: HeliumImpressionData) {
                            logInfo(TAG, "HeliumSdk onImpression $impData")
                        }
                    })
                    onFinished()
                }

                else -> {
                    error("Error initializing Chartboost: ${error.message}")
                }
            }
        }
    }
}


private const val TAG = "ChartBoo"