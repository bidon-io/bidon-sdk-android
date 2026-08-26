package org.bidon.zmaticoo.impl

import android.app.Activity
import com.maticoo.sdk.ad.interstitial.InterstitialAd
import com.maticoo.sdk.ad.interstitial.InterstitialAdListener
import com.zmaticoo.sdk.base.common.MaticooIds
import com.zmaticoo.sdk.flow.model.ComponentError
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
import org.bidon.zmaticoo.ext.asBidonError

/**
 * Created by Bidon Team on 12/01/2026.
 */
internal class ZmaticooInterstitialImpl :
    AdSource.Interstitial<ZmaticooFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var placementId: String? = null

    override val isAdReadyToShow: Boolean
        get() = placementId?.let { InterstitialAd.isReady(it) } ?: false

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> =
        auctionParamsScope {
            ZmaticooFullscreenAuctionParams(activity, adUnit)
        }

    override fun load(adParams: ZmaticooFullscreenAuctionParams) {
        logInfo(TAG, "Starting with $adParams: $this")

        val validatedPlacementId =
            adParams.placementId ?: return emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId,
                        "placementId is null"
                    )
                )
            )
        placementId = validatedPlacementId
        val payload =
            adParams.payload ?: return emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId,
                        "payload is null"
                    )
                )
            )

        InterstitialAd.setAdListener(
            validatedPlacementId,
            object : InterstitialAdListener() {
                override fun onAdLoadSuccess(adId: MaticooIds?) {
                    logInfo(TAG, "onAdLoadSuccess")
                    emitEvent(AdEvent.Fill(getAd() ?: return))
                }

                override fun onAdLoadFailed(
                    maticooIds: MaticooIds,
                    componentError: ComponentError
                ) {
                    logInfo(TAG, "onAdLoadFailed with error: $componentError")
                    emitEvent(AdEvent.LoadFailed(componentError.asBidonError()))
                }

                override fun onAdDisplayed(adId: MaticooIds?) {
                    logInfo(TAG, "onAdDisplayed")
                    getAd()?.let {
                        emitEvent(AdEvent.Shown(it))
                        emitEvent(
                            AdEvent.PaidRevenue(
                                ad = it,
                                adValue =
                                AdValue(
                                    adRevenue = adParams.price / 1000.0,
                                    currency = AdValue.USD,
                                    precision = Precision.Precise
                                )
                            )
                        )
                    }
                }

                override fun onAdDisplayFailed(
                    maticooIds: MaticooIds,
                    componentError: ComponentError
                ) {
                    logInfo(TAG, "onAdDisplayFailed with error: $componentError")
                    emitEvent(AdEvent.ShowFailed(componentError.asBidonError()))
                }

                override fun onAdClicked(adId: MaticooIds?) {
                    logInfo(TAG, "onAdClicked")
                    emitEvent(AdEvent.Clicked(getAd() ?: return))
                }

                override fun onAdClosed(adId: MaticooIds?) {
                    logInfo(TAG, "onAdClosed")
                    emitEvent(AdEvent.Closed(getAd() ?: return))
                }
            }
        )

        InterstitialAd.loadAd(validatedPlacementId, payload)
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")

        val id = placementId ?: return emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))

        if (isAdReadyToShow) {
            InterstitialAd.showAd(id)
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        placementId?.let {
            InterstitialAd.setAdListener(it, object : InterstitialAdListener() {})
            InterstitialAd.destroy(it)
        }
        placementId = null
    }
}

private const val TAG = "ZmaticooInterstitialImpl"
