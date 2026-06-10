package org.bidon.yandex.impl

import android.content.Context
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader

internal val singleLoader: YandexLoader by lazy { YandexLoaderImpl() }

internal class YandexLoaderImpl : YandexLoader {

    private var interstitialAdLoader: InterstitialAdLoader? = null
    private var rewardedAdLoader: RewardedAdLoader? = null

    override fun requestInterstitialAd(
        context: Context,
        adRequest: AdRequest,
        adLoadListener: InterstitialAdLoadListener
    ) {
        val interstitialAdLoader = interstitialAdLoader ?: createInterstitialAdLoader(context)
        interstitialAdLoader.loadAd(adRequest, adLoadListener)
    }

    override fun requestRewardedAd(
        context: Context,
        adRequest: AdRequest,
        adLoadListener: RewardedAdLoadListener
    ) {
        val rewardedAdLoader = rewardedAdLoader ?: createRewardedAdLoader(context)
        rewardedAdLoader.loadAd(adRequest, adLoadListener)
    }

    private fun createInterstitialAdLoader(context: Context): InterstitialAdLoader {
        return InterstitialAdLoader(context).also {
            this.interstitialAdLoader = it
        }
    }

    private fun createRewardedAdLoader(context: Context): RewardedAdLoader {
        return RewardedAdLoader(context).also {
            this.rewardedAdLoader = it
        }
    }
}

internal interface YandexLoader {
    fun requestInterstitialAd(
        context: Context,
        adRequest: AdRequest,
        adLoadListener: InterstitialAdLoadListener
    )

    fun requestRewardedAd(
        context: Context,
        adRequest: AdRequest,
        adLoadListener: RewardedAdLoadListener
    )
}
