package org.bidon.vkads.impl

import android.app.Activity
import com.my.target.common.models.IAdLoadingError
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
import org.bidon.vkads.ext.asBidonError
import com.my.target.ads.InterstitialAd as MyTargetInterstitialAd

internal class VkAdsInterstitialImpl :
    AdSource.Interstitial<VkAdsFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var interstitialAd: MyTargetInterstitialAd? = null

    override val isAdReadyToShow: Boolean
        get() = interstitialAd != null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            VkAdsFullscreenAuctionParams(
                activity = auctionParamsScope.activity,
                adUnit = adUnit,
            )
        }
    }

    override fun load(adParams: VkAdsFullscreenAuctionParams) {
        val slotId = adParams.slotId
            ?: return emitEvent(AdEvent.LoadFailed(BidonError.IncorrectAdUnit(demandId = demandId, message = "slotId")))

        val interstitialAd = MyTargetInterstitialAd(slotId, adParams.activity)
            .also { interstitialAd = it }
        interstitialAd.customParams.setCustomParam("mediation", adParams.mediation)
        interstitialAd.listener = object : MyTargetInterstitialAd.InterstitialAdListener {
            override fun onLoad(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onLoad: $this")
                emitEvent(AdEvent.Fill(getAd() ?: return))
            }

            override fun onNoAd(error: IAdLoadingError, interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onNoAd: ${error.code} ${error.message}. $this")
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
                logInfo(TAG, "onVideoCompleted: $this")
            }

            override fun onDisplay(interstitial: MyTargetInterstitialAd) {
                logInfo(TAG, "onDisplay: $this")
                getAd()?.let {
                    emitEvent(AdEvent.Shown(it))
                    emitEvent(
                        AdEvent.PaidRevenue(
                            ad = it,
                            adValue = AdValue(
                                adRevenue = adParams.price,
                                currency = AdValue.USD,
                                precision = Precision.Precise
                            )
                        )
                    )
                }
            }
        }
        if (adParams.adUnit.bidType == BidType.RTB) {
            val payload = adParams.payload ?:
                return emitEvent(AdEvent.LoadFailed(BidonError.IncorrectAdUnit(demandId = demandId, message = "payload")))
            interstitialAd.loadFromBid(payload)
        } else {
            interstitialAd.load()
        }
    }

    override fun show(activity: Activity) {
        val interstitialAd = interstitialAd
        if (interstitialAd != null) {
            interstitialAd.show()
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        }
    }

    override fun destroy() {
        interstitialAd?.destroy()
        interstitialAd = null
    }
}

private const val TAG = "MyTargetInterstitialImpl"
