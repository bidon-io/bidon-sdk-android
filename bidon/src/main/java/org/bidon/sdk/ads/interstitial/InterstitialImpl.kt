package org.bidon.sdk.ads.interstitial

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.ad
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheProvider
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get

internal class InterstitialImpl(
    dispatcher: CoroutineDispatcher = SdkDispatchers.Main,
    private val auctionKey: String? = null,
    private val demandAd: DemandAd = DemandAd(AdType.Interstitial)
) : Interstitial, Extras by demandAd {

    private val scope: CoroutineScope by lazy { CoroutineScope(dispatcher) }
    private val listener: InterstitialListener by lazy { getInterstitialListener() }
    private val adCache: AdCache get() = get<AdCacheProvider>().provide(demandAd)

    private var cacheJob: Job? = null
    private var observeCallbacksJob: Job? = null

    private var winner: AdSource.Interstitial<*>? = null
    private var userListener: InterstitialListener? = null

    override fun isReady(): Boolean {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return false
        }
        return adCache.peek()?.isAdReadyToShow == true
    }

    override fun setInterstitialListener(listener: InterstitialListener) {
        logInfo(TAG, "Set interstitial listener")
        this.userListener = listener
    }

    override fun loadAd(activity: Activity, pricefloor: Double) {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            listener.onAdLoadFailed(null, BidonError.SdkNotInitialized)
            return
        }
        if (cacheJob?.isActive != true) {
            cacheJob = scope.launch {
                logInfo(TAG, "Load (pricefloor=$pricefloor)")
                adCache.cache(
                    demandAd = demandAd,
                    adTypeParam = AdTypeParam.Interstitial(
                        activity = activity,
                        pricefloor = pricefloor,
                        auctionKey = auctionKey,
                    ),
                    onSuccess = { adSource, auctionInfo ->
                        listener.onAdLoaded(
                            ad = requireNotNull(adSource.ad) { "[Ad] should exist when action succeeds" },
                            auctionInfo = auctionInfo
                        )
                    },
                    onFailure = { auctionInfo, cause ->
                        listener.onAdLoadFailed(
                            auctionInfo = auctionInfo,
                            cause = cause.asBidonErrorOrUnspecified()
                        )
                    }
                )
            }
        } else {
            logInfo(TAG, "Load is already in progress.")
        }
    }

    override fun showAd(activity: Activity) {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            listener.onAdShowFailed(BidonError.SdkNotInitialized)
            return
        }
        logInfo(TAG, "Show")
        activity.runOnUiThread {
            val adSource = (adCache.pop() as? AdSource.Interstitial).also { winner = it }
            if (adSource?.isAdReadyToShow == true) {
                subscribeToWinner(adSource)
                adSource.setDemandAd(demandAd)
                adSource.show(activity)
            } else {
                logInfo(TAG, "Show failed. Ad not ready.")
                listener.onAdShowFailed(BidonError.AdNotReady)
            }
        }
    }

    @Deprecated("With ad caching logic, it works incorrectly")
    override fun notifyLoss(winnerDemandId: String, winnerEcpm: Double) {
        logInfo(TAG, "Notify loss ($winnerDemandId, $winnerEcpm)")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return
        }
        // Mock implementation
        // This method is deprecated and should not be used.
    }

    @Deprecated("With ad caching logic, it works incorrectly")
    override fun notifyWin() {
        logInfo(TAG, "Notify win")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return
        }
        // Mock implementation
        // This method is deprecated and should not be used.
    }

    override fun destroyAd() {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            cacheJob?.cancel()
            cacheJob = null
            observeCallbacksJob?.cancel()
            observeCallbacksJob = null

            winner?.destroy()
            winner = null
            userListener = null
        }
    }

    /**
     * Private
     */

    private fun subscribeToWinner(adSource: AdSource<*>) {
        require(adSource is AdSource.Interstitial)
        observeCallbacksJob = adSource.adEvent.onEach { adEvent ->
            when (adEvent) {
                is AdEvent.Fill,
                is AdEvent.LoadFailed,
                is AdEvent.OnReward -> {
                    // do nothing
                }

                is AdEvent.Shown -> {
                    listener.onAdShown(adEvent.ad)
                    adSource.sendShowImpression()
                }

                is AdEvent.PaidRevenue -> {
                    listener.onRevenuePaid(adEvent.ad, adEvent.adValue)
                }

                is AdEvent.ShowFailed -> {
                    listener.onAdShowFailed(adEvent.cause)
                }

                is AdEvent.Clicked -> {
                    listener.onAdClicked(adEvent.ad)
                    adSource.sendClickImpression()
                }

                is AdEvent.Closed -> {
                    listener.onAdClosed(adEvent.ad)
                    observeCallbacksJob?.cancel()
                    observeCallbacksJob = null
                }

                is AdEvent.Expired -> {
                    listener.onAdExpired(adEvent.ad)
                    destroyAd()
                }
            }
        }.launchIn(scope)
    }

    private fun getInterstitialListener() = object : InterstitialListener {
        override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
            userListener?.onAdLoaded(ad, auctionInfo)
        }

        override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) {
            userListener?.onAdLoadFailed(auctionInfo, cause)
        }

        override fun onAdShowFailed(cause: BidonError) {
            userListener?.onAdShowFailed(cause)
        }

        override fun onAdShown(ad: Ad) {
            userListener?.onAdShown(ad)
        }

        override fun onAdClicked(ad: Ad) {
            userListener?.onAdClicked(ad)
        }

        override fun onAdClosed(ad: Ad) {
            userListener?.onAdClosed(ad)
        }

        override fun onAdExpired(ad: Ad) {
            userListener?.onAdExpired(ad)
        }

        override fun onRevenuePaid(ad: Ad, adValue: AdValue) {
            userListener?.onRevenuePaid(ad, adValue)
        }
    }
}

private const val TAG = "Interstitial"
