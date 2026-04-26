package org.bidon.gma.impl

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdRequest
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.gma.asBidonError
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
    private val getFullScreenEventCallback: GetFullScreenEventCallbackUseCase = GetFullScreenEventCallbackUseCase(),
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
        val adRequest = RewardedAdRequest.Builder(adUnitId).build()
        RewardedAd.load(adRequest, object : AdLoadCallback<RewardedAd> {
            override fun onAdFailedToLoad(error: AdLoadError) {
                logInfo(TAG, "onAdFailedToLoad: $error")
                emitEvent(AdEvent.LoadFailed(error.asBidonError()))
            }

            override fun onAdLoaded(ad: RewardedAd) {
                logInfo(TAG, "onAdLoaded: $ad")
                this@GmaRewardedImpl.rewardedAd = ad
                ad.adEventCallback = getFullScreenEventCallback.createRewardedCallback(
                    adEventFlow = this@GmaRewardedImpl,
                    getAd = { getAd() },
                    onClosed = { this@GmaRewardedImpl.rewardedAd = null }
                )
                getAd()?.let { emitEvent(AdEvent.Fill(it)) }
            }
        })
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        val ad = rewardedAd
        if (ad == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        } else {
            ad.show(activity, OnUserEarnedRewardListener { reward ->
                logInfo(TAG, "onUserEarnedReward: $reward")
                getAd()?.let {
                    emitEvent(AdEvent.OnReward(it, Reward(reward.type, reward.amount)))
                }
            })
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        rewardedAd = null
    }
}

private const val TAG = "GmaRewarded"
