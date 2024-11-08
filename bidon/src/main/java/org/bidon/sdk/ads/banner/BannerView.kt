package org.bidon.sdk.ads.banner

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.R
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.ad
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.helper.AdLifecycle
import org.bidon.sdk.ads.banner.helper.impl.dpToPx
import org.bidon.sdk.ads.banner.helper.wrapUserBannerListener
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.visibilitytracker.VisibilityTracker

/**
 * Created by Bidon Team on 06/02/2023.
 */
class BannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAtt: Int = 0,
    val auctionKey: String? = null,
    private val demandAd: DemandAd = DemandAd(AdType.Banner),
) : FrameLayout(context, attrs, defStyleAtt), BannerAd, Extras by demandAd {

    var format: BannerFormat = BannerFormat.Banner
        private set

    private val scope: CoroutineScope by lazy { CoroutineScope(SdkDispatchers.Main) }
    private val listener: BannerListener by lazy { wrapUserBannerListener(userListener = { userListener }) }
    private val visibilityTracker: VisibilityTracker by lazy { get() }
    private val adLifecycleFlow = MutableStateFlow(AdLifecycle.Created)

    private var userListener: BannerListener? = null
    private var observeCallbacksJob: Job? = null
    private var winner: AdSource.Banner<*>? = null

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.BannerView, 0, 0).apply {
            try {
                getInteger(R.styleable.BannerView_bannerSize, 0).let {
                    when (it) {
                        1 -> setBannerFormat(BannerFormat.Banner)
                        3 -> setBannerFormat(BannerFormat.LeaderBoard)
                        4 -> setBannerFormat(BannerFormat.MRec)
                        5 -> setBannerFormat(BannerFormat.Adaptive)
                    }
                }
            } finally {
                recycle()
            }
        }
    }

    private var internalAdSize: AdSize? = null

    override val adSize: AdSize?
        get() {
            val adSource = adCache.peek() as AdSource.Banner<*>?
            return internalAdSize ?: adSource?.getAdView()?.let { holder ->
                AdSize(widthDp = holder.widthDp, heightDp = holder.heightDp)
                    .also { internalAdSize = it }
            }
        }

    override fun isReady(): Boolean {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return false
        }
        return adCache.peek()?.isAdReadyToShow == true
    }

    override fun setBannerFormat(bannerFormat: BannerFormat) {
        logInfo(TAG, "Set banner format: $bannerFormat")
        this.format = bannerFormat
    }

    override fun setBannerListener(listener: BannerListener) {
        logInfo(TAG, "Set banner listener")
        userListener = listener
    }

    override fun loadAd(activity: Activity, pricefloor: Double) {
        logInfo(TAG, "LoadAd. $this. ${Thread.currentThread()}")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            listener.onAdLoadFailed(null, BidonError.SdkNotInitialized)
            return
        }
        logInfo(TAG, "Load (pricefloor=$pricefloor)")
        adLifecycleFlow.value = AdLifecycle.Loading
        adCache.cache(
            demandAd = demandAd,
            adTypeParam = AdTypeParam.Banner(
                activity = activity,
                pricefloor = pricefloor,
                auctionKey = auctionKey,
                bannerFormat = format,
                containerWidth = width.toFloat()
            ),
            onSuccess = { adSource, auctionInfo ->
                adLifecycleFlow.value = AdLifecycle.Loaded
                subscribeToWinner(adSource)
                listener.onAdLoaded(
                    ad = requireNotNull(adSource.ad) { "[Ad] should exist when action succeeds" },
                    auctionInfo = auctionInfo
                )
            },
            onFailure = { auctionInfo, cause ->
                adLifecycleFlow.value = AdLifecycle.LoadingFailed
                listener.onAdLoadFailed(
                    auctionInfo = auctionInfo,
                    cause = cause.asBidonErrorOrUnspecified()
                )
            }
        )
    }

    override fun showAd() {
        logInfo(TAG, "ShowAd invoked. ${Thread.currentThread()}")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            listener.onAdShowFailed(BidonError.SdkNotInitialized)
            return
        }
        when (adLifecycleFlow.value) {
            AdLifecycle.Created,
            AdLifecycle.Loading -> {
                // do nothing
            }

            AdLifecycle.Loaded -> {
                scope.launch(Dispatchers.Main.immediate) {
                    adLifecycleFlow.value = AdLifecycle.Displaying
                    val adSource = (adCache.pop() as? AdSource.Banner<*>).also { winner = it }
                    if (adSource?.isAdReadyToShow == true) {
                        addViewOnScreen(adSource)
                    } else {
                        logInfo(TAG, "Show failed. Ad not ready.")
                        adLifecycleFlow.value = AdLifecycle.DisplayingFailed
                        listener.onAdShowFailed(BidonError.AdNotReady)
                    }
                }
            }

            AdLifecycle.LoadingFailed -> {
                logInfo(TAG, "Show failed. Ad not ready.")
                listener.onAdShowFailed(BidonError.AdNotReady)
            }

            AdLifecycle.Displaying -> {
                logInfo(TAG, "Show failed. Ad already displaying.")
            }

            AdLifecycle.Displayed -> {
                logInfo(TAG, "Show failed. Ad already displayed.")
            }

            AdLifecycle.DisplayingFailed -> {
                logInfo(TAG, "Show failed. Ad displaying failed.")
                listener.onAdShowFailed(BidonError.AdNotReady)
            }

            AdLifecycle.Destroyed -> {
                logInfo(TAG, "Show failed. Ad destroyed.")
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
        adCache.pop()?.sendLoss(winnerDemandId, winnerEcpm)
        destroyAd()
    }

    @Deprecated("With ad caching logic, it works incorrectly")
    override fun notifyWin() {
        logInfo(TAG, "Notify win")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return
        }
        adCache.peek()?.sendWin()
    }

    override fun destroyAd() {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            adLifecycleFlow.value = AdLifecycle.Destroyed
            visibilityTracker.stop()
            adCache.clear()
            winner?.destroy()
            winner = null
            observeCallbacksJob?.cancel()
            observeCallbacksJob = null
            removeAllViews()
        }
    }

    /**
     * Private
     */

    private fun FrameLayout.addViewOnScreen(adSource: AdSource.Banner<*>) {
        val frameLayout = this
        // add AdView to Screen
        frameLayout.removeAllViews()
        val adViewHolder: AdViewHolder = adSource.getAdView() ?: run {
            logError(TAG, "No AdView found.", NullPointerException())
            return
        }
        val networkAdview = adViewHolder.networkAdview
        val layoutParams =
            LayoutParams(adViewHolder.widthDp.dpToPx, adViewHolder.heightDp.dpToPx, Gravity.CENTER)
        frameLayout.addView(networkAdview, layoutParams)
        frameLayout.visibility = VISIBLE
        networkAdview.visibility = VISIBLE
        logInfo(TAG, "View added(${adSource.demandId.demandId}): $networkAdview. Size(${adViewHolder.widthDp}, ${adViewHolder.heightDp})")
        checkBannerShown(networkAdview) {
            adLifecycleFlow.value = AdLifecycle.Displayed
            adSource.ad?.let { listener.onAdShown(ad = it) }
            adSource.sendShowImpression()
        }
    }

    private fun subscribeToWinner(adSource: AdSource<*>) {
        require(adSource is AdSource.Banner)
        observeCallbacksJob = adSource.adEvent.onEach { adEvent ->
            when (adEvent) {
                is AdEvent.Fill,
                is AdEvent.LoadFailed,
                is AdEvent.OnReward,
                is AdEvent.Closed -> {
                    // do nothing
                }

                is AdEvent.Shown -> {
                    // banners do not invoke onShown callback
                }

                is AdEvent.ShowFailed -> {
                    adLifecycleFlow.value = AdLifecycle.DisplayingFailed
                    listener.onAdShowFailed(adEvent.cause)
                }

                is AdEvent.PaidRevenue -> {
                    listener.onRevenuePaid(adEvent.ad, adEvent.adValue)
                }

                is AdEvent.Clicked -> {
                    listener.onAdClicked(adEvent.ad)
                    adSource.sendClickImpression()
                }

                is AdEvent.Expired -> {
                    listener.onAdExpired(adEvent.ad)
                    destroyAd()
                }
            }
        }.launchIn(scope)
    }

    private fun checkBannerShown(networkAdview: View, onBannerShown: () -> Unit) {
        visibilityTracker.start(view = networkAdview) {
            onBannerShown.invoke()
        }
    }

    private companion object {
        private const val TAG = "BannerView"
        private val adCache: AdCache by lazy { get<AdCache> { params(AdType.Banner) } }
    }
}
