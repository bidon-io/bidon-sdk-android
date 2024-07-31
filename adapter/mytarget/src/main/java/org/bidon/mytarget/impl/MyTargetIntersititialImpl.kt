package org.bidon.mytarget.impl

import android.app.Activity
import android.content.Context
import com.my.target.common.models.IAdLoadingError
import org.bidon.mytarget.ext.asBidonError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.stats.models.BidType
import com.my.target.ads.InterstitialAd as MyTargetInterstitialAd

class MyTargetIntersititialImpl :
    AdSource.Interstitial<MyTargetFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var interstitialAd: MyTargetInterstitialAd? = null
    private var context: Context? = null

    override fun show(activity: Activity) {
        interstitialAd?.show(activity)
    }

    override val isAdReadyToShow: Boolean
        get() = interstitialAd != null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        context = auctionParamsScope.activity
        return auctionParamsScope {
            MyTargetFullscreenAuctionParams(
                adUnit = adUnit,
            )
        }
    }

    override fun load(adParams: MyTargetFullscreenAuctionParams) {
        adParams.slotId ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "slotId")
                )
            )
            return
        }
        val context: Context = context ?: run {
            emitEvent(AdEvent.LoadFailed(BidonError.NoContextFound))
            return
        }
        val interstitialAd = MyTargetInterstitialAd(adParams.slotId, context).also {
            it.customParams.setCustomParam("mediation", adParams.mediation)
            interstitialAd = it
        }
        interstitialAd.listener = object : MyTargetInterstitialAd.InterstitialAdListener {
            override fun onLoad(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onLoad: $this")
                emitEvent(AdEvent.Fill(getAd() ?: return))
            }

            override fun onNoAd(error: IAdLoadingError, interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "Error while loading ad: ${error.code} ${error.message}. $this")
                emitEvent(AdEvent.LoadFailed(error.asBidonError()))
            }

            override fun onClick(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onClick: $this")
                emitEvent(AdEvent.Clicked(getAd() ?: return))
            }

            override fun onDismiss(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onDismiss: $this")
                emitEvent(AdEvent.Closed(getAd() ?: return))
            }

            override fun onVideoCompleted(interstitial: MyTargetInterstitialAd) {
            }

            override fun onDisplay(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onVideoCompleted: $this")
                val ad = getAd() ?: return
                emitEvent(
                    AdEvent.PaidRevenue(
                        ad = ad,
                        adValue = AdValue(
                            adRevenue = ad.ecpm / 1000.0,
                            precision = Precision.Estimated,
                            currency = AdValue.USD,
                        )
                    )
                )
                logInfo(TAG, "onAdDisplayed: $this")
                emitEvent(AdEvent.Shown(ad))
            }
        }
        if (adParams.adUnit.bidType == BidType.RTB) {
            adParams.payload ?: run {
                emitEvent(
                    AdEvent.LoadFailed(
                        BidonError.IncorrectAdUnit(demandId = demandId, message = "payload")
                    )
                )
                return
            }
            interstitialAd.loadFromBid(adParams.payload)
        } else {
            interstitialAd.load()
        }
    }

    override fun destroy() {
        interstitialAd?.destroy()
        interstitialAd = null
        context = null
    }
}

private const val TAG = "MyTargetInterstitialImpl"