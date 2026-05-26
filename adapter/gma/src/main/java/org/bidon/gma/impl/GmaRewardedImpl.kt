package org.bidon.gma.impl

import android.app.Activity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
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
    private val getFullScreenContentCallback: GetFullScreenContentCallbackUseCase = GetFullScreenContentCallbackUseCase(),
    private val obtainAdAuctionParams: GetAdAuctionParamsUseCase = GetAdAuctionParamsUseCase(),
) : AdSource.Rewarded<GmaFullscreenAdAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var rewardedAd: RewardedAd? = null
    private var price: Double? = null

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
        price = adParams.price
        val requestListener = object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                logInfo(TAG, "onAdFailedToLoad: $loadAdError. $this")
                emitEvent(AdEvent.LoadFailed(loadAdError.asBidonError()))
            }

            override fun onAdLoaded(rewardedAd: RewardedAd) {
                logInfo(TAG, "onAdLoaded. RewardedAd=$rewardedAd, $this")
                this@GmaRewardedImpl.rewardedAd = rewardedAd
                adParams.activity.runOnUiThread {
                    rewardedAd.onPaidEventListener = OnPaidEventListener { adValue ->
                        getAd()?.let {
                            emitEvent(
                                AdEvent.PaidRevenue(
                                    ad = it,
                                    adValue = adValue.asBidonAdValue()
                                )
                            )
                        }
                    }
                    rewardedAd.fullScreenContentCallback = getFullScreenContentCallback.createCallback(
                        adEventFlow = this@GmaRewardedImpl,
                        getAd = {
                            getAd()
                        },
                        onClosed = {
                            this@GmaRewardedImpl.rewardedAd = null
                        }
                    )
                    getAd()?.let { emitEvent(AdEvent.Fill(it)) }
                }
            }
        }
        RewardedAd.load(adParams.activity, adUnitId, getAdRequest(), requestListener)
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        val rewardedAd = rewardedAd
        if (rewardedAd == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        } else {
            rewardedAd.show(activity) { rewardItem ->
                logInfo(TAG, "onUserEarnedReward $rewardItem: $this")
                getAd()?.let {
                    emitEvent(AdEvent.OnReward(it, Reward(rewardItem.type, rewardItem.amount)))
                }
            }
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        rewardedAd?.onPaidEventListener = null
        rewardedAd?.fullScreenContentCallback = null
        rewardedAd = null
    }
}

private const val TAG = "GmaRewarded"
