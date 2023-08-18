package org.bidon.demoapp.ui

import android.app.Activity
import android.content.Context
import com.chartboost.heliumsdk.HeliumIlrdObserver
import com.chartboost.heliumsdk.HeliumImpressionData
import com.chartboost.heliumsdk.HeliumSdk
import com.chartboost.heliumsdk.ad.HeliumFullscreenAdListener
import com.chartboost.heliumsdk.ad.HeliumInterstitialAd
import com.chartboost.heliumsdk.domain.ChartboostMediationAdException
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Created by Aleksei Cherniaev on 18/08/2023.
 */
object ChartBoo {
    fun st(coroutineScope: CoroutineScope, activity: Activity) {
        BidonSdk.setLoggerLevel(Logger.Level.Verbose)
        initChartboost(
            context = activity,
        ) {
            logInfo(TAG, "HeliumSdk start")
            loadInterstitial(activity)
            return@initChartboost
        }
    }

    private fun loadInterstitial(activity: Activity) {
        logInfo(TAG, "HeliumFullscreenAdListener loadInterstitial")
        val a = HeliumInterstitialAd(activity,
            placementName = "chartboostTestInterstitial",
            heliumFullscreenAdListener = object : HeliumFullscreenAdListener {
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
        a.load()
    }

    private fun initChartboost(
        context: Context,
        onFinished: () -> Unit

    ) {
        logInfo(TAG, "HeliumSdk initChartboost")
        MobileAds.disableMediationAdapterInitialization(context)
        HeliumSdk.start(
            /* context = */ context,
            /* appId = */ "64d10336ec5b182b9e101000",
            /* appSignature = */ "331d6a248a70f9264c32259512f1321eba47c608"
        ) { error ->
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