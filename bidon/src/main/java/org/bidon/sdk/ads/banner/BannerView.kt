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
import org.bidon.sdk.ads.AuctionInfo
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

    private var auctionInfo: AuctionInfo? = null
    private var winner: AdSource<*>? = null

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
        get() = internalAdSize ?: (winner as? AdSource.Banner)?.getAdView()?.let { holder ->
            AdSize(widthDp = holder.widthDp, heightDp = holder.heightDp).also {
                internalAdSize = it
            }
        }

    override fun isReady(): Boolean {
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            return false
        }
        return adCache.peek()?.isAdReadyToShow == true
                && adLifecycleFlow.value == AdLifecycle.Loaded
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
        if (adLifecycleFlow.compareAndSet(
                expect = AdLifecycle.Created,
                update = AdLifecycle.Loading
            )
        ) {
            conductAuction(activity, pricefloor)
        } else {
            when (adLifecycleFlow.value) {
                AdLifecycle.Loading -> {
                    logInfo(TAG, "Auction already in progress")
                    userListener?.onAdLoadFailed(null, BidonError.AuctionInProgress)
                }

                AdLifecycle.Loaded -> {
                    winner?.ad?.let {
                        logInfo(TAG, "Banner loaded")
                        userListener?.onAdLoaded(
                            ad = it,
                            auctionInfo = requireNotNull(auctionInfo) {
                                "[AuctionInfo] should exist when action succeeds"
                            }
                        )
                    }
                }

                else -> {
                    logInfo(TAG, "Ad State=${adLifecycleFlow.value}")
                }
            }
        }
    }

    override fun showAd() {
        logInfo(TAG, "ShowAd invoked. ${Thread.currentThread()}")
        if (!BidonSdk.isInitialized()) {
            logInfo(TAG, "Sdk is not initialized")
            listener.onAdShowFailed(BidonError.SdkNotInitialized)
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            val adSource = (winner as? AdSource.Banner)
            if (adSource?.isAdReadyToShow == true) {
                addViewOnScreen(adSource)
            } else {
                logInfo(TAG, "AdSource($adSource: no ad view.")
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
        // add AdView to Screen
        removeAllViews()
        val adViewHolder: AdViewHolder = adSource.getAdView() ?: run {
            logError(TAG, "No AdView found.", NullPointerException())
            return
        }
        val layoutParams =
            LayoutParams(adViewHolder.widthDp.dpToPx, adViewHolder.heightDp.dpToPx, Gravity.CENTER)
        addView(adViewHolder.networkAdview, layoutParams)
        this.visibility = VISIBLE
        adViewHolder.networkAdview.visibility = VISIBLE
        logInfo(
            TAG,
            "View added(${adSource.demandId.demandId}): ${adViewHolder.networkAdview}. Size(${adViewHolder.widthDp}, ${adViewHolder.heightDp})"
        )
        checkBannerShown(adViewHolder.networkAdview, onBannerShown = {
            adLifecycleFlow.value = AdLifecycle.Displayed
            adSource.ad?.let { listener.onAdShown(ad = it) }
            adSource.sendShowImpression()
        })
    }

    private fun conductAuction(activity: Activity, pricefloor: Double) {
        logInfo(TAG, "Load (pricefloor=$pricefloor)")
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
                this.winner = adSource
                this.auctionInfo = auctionInfo
                subscribeToWinner(adSource)
                adLifecycleFlow.value = AdLifecycle.Loaded
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

    private fun subscribeToWinner(adSource: AdSource<*>) {
        require(adSource is AdSource.Banner<*>)
        observeCallbacksJob = adSource.adEvent.onEach { adEvent ->
            when (adEvent) {
                is AdEvent.OnReward,
                is AdEvent.Closed,
                is AdEvent.LoadFailed,
                is AdEvent.Fill -> {
                    // do nothing
                }

                is AdEvent.Clicked -> {
                    listener.onAdClicked(adEvent.ad)
                    adSource.sendClickImpression()
                }

                is AdEvent.Shown -> {
                    // banners do not invoke onShown callback
                }

                is AdEvent.PaidRevenue -> listener.onRevenuePaid(adEvent.ad, adEvent.adValue)
                is AdEvent.ShowFailed -> {
                    adLifecycleFlow.value = AdLifecycle.DisplayingFailed
                    listener.onAdLoadFailed(null, adEvent.cause)
                }

                is AdEvent.Expired -> listener.onAdExpired(adEvent.ad)
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
