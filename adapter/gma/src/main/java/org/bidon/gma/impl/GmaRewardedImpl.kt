package org.bidon.gma.impl

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.gma.asBidonError
import org.bidon.gma.ext.asBidonAdValue
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class GmaRewardedImpl(
    private val getAdRequest: GetAdRequestUseCase = GetAdRequestUseCase(),
    private val obtainAdAuctionParams: GetAdAuctionParamsUseCase = GetAdAuctionParamsUseCase(),
) : AdSource.Rewarded<GmaFullscreenAdAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var rewardedAd: RewardedAd? = null

    override val isAdReadyToShow: Boolean
        get() = rewardedAd != null

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
                    BidonError.IncorrectAdUnit(demandId, "adUnitId")
                )
            )
            return
        }

        RewardedAd.load(
            getAdRequest(adUnitId),
            object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    logInfo(TAG, "onAdLoaded. RewardedAd=$ad, $this")
                    rewardedAd = ad
                    adParams.activity.runOnUiThread {
                        ad.adEventCallback = object : RewardedAdEventCallback {
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
                                rewardedAd = null
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
                                    emitEvent(
                                        AdEvent.PaidRevenue(
                                            ad = it,
                                            adValue = adValue.asBidonAdValue()
                                        )
                                    )
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
        val ad = rewardedAd
        if (ad == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        } else {
            ad.show(activity, object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(reward: RewardItem) {
                    logInfo(TAG, "onUserEarnedReward $reward: $this")
                    getAd()?.let {
                        emitEvent(AdEvent.OnReward(it, Reward(reward.type, reward.amount)))
                    }
                }
            })
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        rewardedAd?.adEventCallback = null
        rewardedAd = null
    }
}

private const val TAG = "GmaRewarded"
