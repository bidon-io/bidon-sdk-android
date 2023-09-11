package org.bidon.inmobi.impl

import android.app.Activity
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.InterstitialAdEventListener
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

/**
 * Created by Aleksei Cherniaev on 11/09/2023.
 */
internal class InmobiInterstitialImpl : AdSource.Interstitial<InmobiFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl(),
    Mode.Network {

    private var interstitial: InMobiInterstitial? = null

    override val isAdReadyToShow: Boolean
        get() = interstitial?.isReady == true

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            val lineItem = popLineItem(demandId) ?: error(BidonError.NoAppropriateAdUnitId)
            InmobiFullscreenAuctionParams(
                activity = activity,
                placementId = requireNotNull(lineItem.adUnitId).toLong(),
                price = lineItem.pricefloor,
            )
        }
    }

    override fun load(adParams: InmobiFullscreenAuctionParams) {
        logInfo(TAG, "Starting with $adParams: $this")
        val interstitialAd = InMobiInterstitial(adParams.activity, adParams.placementId, object : InterstitialAdEventListener() {
            override fun onAdLoadSucceeded(interstitial: InMobiInterstitial, adMetaInfo: AdMetaInfo) {
                logInfo(TAG, "onAdLoadSucceeded: $this")
                emitEvent(AdEvent.Fill(getAd(interstitial) ?: return))
            }

            override fun onAdLoadFailed(interstitial: InMobiInterstitial, status: InMobiAdRequestStatus) {
            }

            override fun onAdClicked(interstitial: InMobiInterstitial, map: MutableMap<Any, Any>?) {
            }

            override fun onAdImpression(interstitial: InMobiInterstitial) {
            }

            override fun onAdDisplayed(interstitial: InMobiInterstitial, adMetaInfo: AdMetaInfo) {
            }

            override fun onAdDisplayFailed(interstitial: InMobiInterstitial) {
            }

            override fun onAdDismissed(interstitial: InMobiInterstitial) {
            }
        })
        this.interstitial = interstitialAd
        interstitialAd.load()
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        if (isAdReadyToShow) {
            interstitial?.show()
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.FullscreenAdNotReady))
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy")
        interstitial = null
    }

}

private const val TAG = "InmobiInterstitialImpl"
