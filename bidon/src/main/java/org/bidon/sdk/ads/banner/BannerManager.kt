package org.bidon.sdk.ads.banner

import android.app.Activity
import android.graphics.Point
import android.graphics.PointF
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.render.AdRenderer
import org.bidon.sdk.ads.banner.render.AdRenderer.PositionState
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.WinLossNotifier
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 */
class BannerManager private constructor(
    private val activity: Activity,
    private val bannerView: BannerView,
) : PositionedBanner,
    WinLossNotifier by bannerView,
    Extras by bannerView {

    constructor(
        activity: Activity,
        format: BannerFormat = BannerFormat.Banner,
    ) : this(
        activity = activity,
        bannerView = BannerView(activity).apply {
            setBannerFormat(format)
        }
    ) {
        logInfo(TAG, "Created $this")
    }

    private val loaded = AtomicBoolean(false)
    private val shown = AtomicBoolean(false)
    private val showAfterLoad = AtomicBoolean(false)
    private var positionState: PositionState = PositionState.Default
    private var publisherListener: BannerListener? = null
    private val adRenderer: AdRenderer by lazy { get() }

    override val adSize: AdSize?
        get() = bannerView.adSize

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
        bannerView.setBannerFormat(bannerFormat)
    }

    override fun loadAd(activity: Activity, pricefloor: Double) {
        if (!BidonSdk.isInitialized()) {
            publisherListener?.onAdLoadFailed(BidonError.SdkNotInitialized)
            return
        }
        if (!loaded.getAndSet(true)) {
            logInfo(TAG, "loadAd: $pricefloor")
            bannerView.setBannerListener(
                object : BannerListener {
                    override fun onAdLoaded(ad: Ad) {
                        publisherListener?.onAdLoaded(ad)
                        loaded.set(true)
                        if (showAfterLoad.get()) {
                            showAd()
                        }
                    }

                    override fun onAdLoadFailed(cause: BidonError) {
                        publisherListener?.onAdLoadFailed(cause)
                    }

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
            bannerView.loadAd(activity, pricefloor)
        } else {
            publisherListener?.onAdLoadFailed(BidonError.BannerAdNotReady)
        }
    }

    override fun isReady(): Boolean = bannerView.isReady()

    override fun showAd() {
        logInfo(TAG, "showAd")
        if (!BidonSdk.isInitialized()) {
            publisherListener?.onAdLoadFailed(BidonError.SdkNotInitialized)
            return
        }
        logInfo(TAG, "showAd")
        showAfterLoad.set(true)
        // TODO remove true
        if (true || bannerView.isReady() && !shown.getAndSet(true)) {
            render(
                bannerView = bannerView,
                positionState = positionState,
            )
        } else {
            publisherListener?.onAdShowFailed(BidonError.BannerAdNotReady)
        }
    }

    override fun hideAd() {
        adRenderer.hide()
    }

    override fun destroyAd() {
        hideAd()
        bannerView.destroyAd()
    }

    override fun setBannerListener(listener: BannerListener?) {
        publisherListener = listener
    }

    override fun addExtra(key: String, value: Any?) {
        bannerView.addExtra(key, value)
    }

    override fun getExtras(): Map<String, Any> {
        return bannerView.getExtras()
    }

    private fun render(
        bannerView: BannerView,
        positionState: PositionState,
    ) {
        logInfo(TAG, "render: $positionState, $bannerView")
        val TAG = TAG
        when (positionState) {
            is PositionState.Coordinate -> TODO()
            is PositionState.Place -> {
                adRenderer.render(
                    activity = activity,
                    bannerView = bannerView,
                    positionState = positionState,
                    animate = true,
                    handleConfigurationChanges = false,
                    useSafeArea = true,
                    renderListener = object : AdRenderer.RenderListener {
                        override fun onRendered() {
                            logInfo(TAG, "RenderListener.onRendered")
                        }

                        override fun onRenderFailed() {
                            logInfo(TAG, "RenderListener.onRenderFailed")
                        }

                        override fun onVisibilityIssued() {
                            logInfo(TAG, "RenderListener.onVisibilityIssued")
                        }
                    }
                )
            }
        }
    }
}
