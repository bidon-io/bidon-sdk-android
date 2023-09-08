package org.bidon.sdk.ads.banner

import android.app.Activity
import android.graphics.Point
import android.graphics.PointF
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.refresh.BannersCache
import org.bidon.sdk.ads.banner.render.AdRenderer
import org.bidon.sdk.ads.banner.render.AdRenderer.PositionState
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.WinLossNotifier
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 */
class BannerManager private constructor(
    private val bannersCache: BannersCache,
    private val extras: Extras
) : PositionedBanner,
    WinLossNotifier,
    Extras {

    constructor() : this(
        bannersCache = get(),
        extras = get()
    ) {
        logInfo(TAG, "Created $this")
    }

    private var weakActivity = WeakReference<Activity>(null)
    private var nextBannerView: BannerView? = null
    private var currentBannerView: BannerView? = null
    private var bannerFormat: BannerFormat? = null
    private val showAfterLoad = AtomicBoolean(false)
    private var positionState: PositionState = PositionState.Default
    private var publisherListener: BannerListener? = null
    private val adRenderer: AdRenderer by lazy { get() }

    override val adSize: AdSize?
        get() = currentBannerView?.adSize

    /**
     * Positioning functions
     */
    override fun setPosition(position: BannerPosition) {
        logInfo(TAG, "setPosition: $position")
        positionState = PositionState.Place(position)
    }

    override fun setCustomPosition(offset: Point, rotation: Int, anchor: PointF) {
        logInfo(TAG, "setPosition by coordinates Offset($offset), Rotation($rotation), Anchor($anchor)")
        positionState = PositionState.Coordinate(
            AdRenderer.AdContainerParams(offset, rotation, anchor)
        )
    }

    /**
     * BannerView's functions
     */
    override fun setBannerFormat(bannerFormat: BannerFormat) {
        this.bannerFormat = bannerFormat
    }

    override fun loadAd(activity: Activity, pricefloor: Double) {
        weakActivity = WeakReference(activity)
        if (!BidonSdk.isInitialized()) {
            publisherListener?.onAdLoadFailed(BidonError.SdkNotInitialized)
            return
        }
        nextBannerView = null
        bannersCache.get(
            activity = activity,
            format = bannerFormat ?: BannerFormat.Banner,
            pricefloor = pricefloor,
            extras = extras,
            onLoaded = { ad, bannerView ->
                this.nextBannerView = bannerView
                publisherListener?.onAdLoaded(ad)
                if (showAfterLoad.getAndSet(false)) {
                    showAd()
                }
            },
            onFailed = { cause ->
                publisherListener?.onAdLoadFailed(cause)
            }
        )
    }

    override fun isReady(): Boolean = currentBannerView?.isReady() == true

    override fun showAd() {
        logInfo(TAG, "showAd curr = $currentBannerView")
        logInfo(TAG, "showAd next = $nextBannerView")
        if (!BidonSdk.isInitialized()) {
            publisherListener?.onAdLoadFailed(BidonError.SdkNotInitialized)
            return
        }
        logInfo(TAG, "showAd")
        val banner = nextBannerView ?: currentBannerView
        if (banner?.isReady() != true) {
            showAfterLoad.set(true)
            publisherListener?.onAdShowFailed(BidonError.BannerAdNotReady)
            return
        }
        nextBannerView = null
        currentBannerView = banner
        banner.setBannerListener(
            object : BannerListener {
                override fun onAdLoaded(ad: Ad) {}
                override fun onAdLoadFailed(cause: BidonError) {}

                override fun onAdShown(ad: Ad) {
                    publisherListener?.onAdShown(ad)
                }

                override fun onAdClicked(ad: Ad) {
                    publisherListener?.onAdClicked(ad)
                }

                override fun onAdExpired(ad: Ad) {
                    publisherListener?.onAdExpired(ad)
                }

                override fun onRevenuePaid(ad: Ad, adValue: AdValue) {
                    publisherListener?.onRevenuePaid(ad, adValue)
                }

                override fun onAdShowFailed(cause: BidonError) {
                    publisherListener?.onAdShowFailed(cause)
                }
            }
        )
        render(
            bannerView = banner,
            positionState = positionState,
        )
    }

    override fun hideAd() {
        adRenderer.hide()
    }

    override fun destroyAd() {
        hideAd()
        currentBannerView?.destroyAd()
        currentBannerView = null
        nextBannerView?.destroyAd()
        nextBannerView = null
    }

    override fun setBannerListener(listener: BannerListener?) {
        publisherListener = listener
    }

    override fun addExtra(key: String, value: Any?) {
        extras.addExtra(key, value)
        nextBannerView?.addExtra(key, value)
        currentBannerView?.addExtra(key, value)
    }

    override fun getExtras(): Map<String, Any> {
        return extras.getExtras()
    }

    override fun notifyLoss(winnerDemandId: String, winnerEcpm: Double) {
        nextBannerView?.notifyLoss(winnerDemandId, winnerEcpm)
        nextBannerView = null
    }

    override fun notifyWin() {
        nextBannerView?.notifyWin()
    }

    private fun render(
        bannerView: BannerView,
        positionState: PositionState,
    ) {
        val activity = weakActivity.get() ?: run {
            publisherListener?.onAdShowFailed(BidonError.NoContextFound)
            return
        }
        val TAG = TAG
        adRenderer.render(
            activity = activity,
            bannerView = bannerView,
            positionState = positionState,
            animate = true,
            handleConfigurationChanges = false,
            renderListener = object : AdRenderer.RenderListener {
                override fun onRendered() {}
                override fun onRenderFailed() {}
                override fun onVisibilityIssued() {
                    bannerView.destroyAd()
                    publisherListener?.onAdShowFailed(BidonError.BannerAdNotReady)
                    logInfo(TAG, "RenderListener.onVisibilityIssued")
                }
            }
        )
    }
}
