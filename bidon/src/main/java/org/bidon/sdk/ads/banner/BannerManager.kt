package org.bidon.sdk.ads.banner

import android.app.Activity
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.render.AdRenderer
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
    BannerAd,
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
    private var rotation = 0
    private var positionState: Banner.PositionState = Banner.PositionState.Default
    private var publisherListener: BannerListener? = null
    private val adRenderer: AdRenderer by lazy { get() }

    override val adSize: AdSize?
        get() = bannerView.adSize

    /**
     * Positioning functions
     */
    override fun setPosition(position: BannerPosition) {
        logInfo(TAG, "setPosition: $position")
        positionState = Banner.PositionState.Place(position)
    }

    override fun setPosition(left: Int, top: Int) {
        logInfo(TAG, "setPosition by coordinates: [$left, $top]")
        positionState = Banner.PositionState.Coordinate(left, top)
    }

    override fun setRotation(degree: Int) {
        logInfo(TAG, "setRotation: $degree")
        this.rotation = degree
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
        if (true || bannerView.isReady() && !shown.getAndSet(true)) {
            render(
                bannerView = bannerView,
                positionState = positionState,
                rotation = rotation
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

    private fun render(bannerView: BannerView, positionState: Banner.PositionState, rotation: Int) {
        logInfo(TAG, "render: $positionState, $bannerView")
        when (positionState) {
            is Banner.PositionState.Coordinate -> TODO()
            is Banner.PositionState.Place -> {
                adRenderer.render(
                    activity = activity,
                    bannerView = bannerView,
                    position = positionState.position,
                    animate = true,
                    isRotated = positionState.position in arrayOf(BannerPosition.MiddleLeft, BannerPosition.MiddleRight),
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