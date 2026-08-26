package org.bidon.zmaticoo.impl

import android.app.Activity
import com.maticoo.sdk.ad.video.RewardedVideoAd
import com.maticoo.sdk.ad.video.RewardedVideoListener
import com.zmaticoo.sdk.ads.rewardads.MaticooRewardInfo
import com.zmaticoo.sdk.base.common.MaticooIds
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.rewarded.Reward
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
internal class ZmaticooRewardedImpl :
    AdSource.Rewarded<ZmaticooFullscreenAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var rewardedAd: RewardedVideoAd? = null

    override val isAdReadyToShow: Boolean
        get() = rewardedAd?.isReady() ?: false

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
        val payload =
            adParams.payload ?: return emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId,
                        "payload is null"
                    )
                )
            )

        val ad = RewardedVideoAd(validatedPlacementId)
        rewardedAd = ad

        ad.setAdListener(
            object : RewardedVideoListener() {
                override fun onRewardedVideoAdLoadSuccess(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdLoadSuccess")
                    emitEvent(AdEvent.Fill(getAd() ?: return))
                }

                override fun onRewardedVideoAdLoadFailed(
                    maticooIds: MaticooIds,
                    error: ComponentError
                ) {
                    logInfo(TAG, "onRewardedVideoAdLoadFailed: $error")
                    emitEvent(AdEvent.LoadFailed(error.asBidonError()))
                }

                override fun onRewardedVideoAdShowed(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdShowed")
                    getAd()?.let {
                        emitEvent(AdEvent.Shown(it))
                        emitEvent(
                            AdEvent.PaidRevenue(
                                ad = it,
                                adValue = AdValue(
                                    adRevenue = adParams.price / 1000.0,
                                    currency = AdValue.USD,
                                    precision = Precision.Precise
                                )
                            )
                        )
                    }
                }

                override fun onRewardedVideoAdShowFailed(maticooIds: MaticooIds, error: ComponentError) {
                    logInfo(TAG, "onRewardedVideoAdShowFailed: $error")
                    emitEvent(AdEvent.ShowFailed(error.asBidonError()))
                }

                override fun onRewardedVideoAdStarted(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdStarted")
                }

                override fun onRewardedVideoAdCompleted(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdCompleted")
                }

                override fun onRewardedVideoAdClicked(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdClicked")
                    emitEvent(AdEvent.Clicked(getAd() ?: return))
                }

                override fun onRewardedVideoAdRewarded(
                    maticooIds: MaticooIds,
                    rewardInfo: MaticooRewardInfo
                ) {
                    logInfo(TAG, "onRewardedVideoAdRewarded")
                    emitEvent(
                        AdEvent.OnReward(
                            getAd() ?: return,
                            Reward(
                                label = rewardInfo.rewardName,
                                amount = rewardInfo.rewardAmount.toIntOrNull() ?: 0
                            )
                        )
                    )
                }

                override fun onRewardedVideoAdClosed(adId: MaticooIds?) {
                    logInfo(TAG, "onRewardedVideoAdClosed")
                    emitEvent(AdEvent.Closed(getAd() ?: return))
                }
            })

        ad.loadAd(payload)
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")

        val ad = rewardedAd ?: return emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))

        if (isAdReadyToShow) {
            ad.showAd()
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        rewardedAd?.let {
            it.setAdListener(object : RewardedVideoListener() {})
            it.destroy()
        }
        rewardedAd = null
    }
}

private const val TAG = "ZmaticooRewardedImpl"