package org.bidon.sdk.ads.banner

import android.app.Activity
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.banner.refresh.BannersCache
import org.bidon.sdk.ads.banner.render.AdRenderer
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Aleksei Cherniaev on 04/09/2023.
 */
class Banner(
    private val activity: Activity,
    private val format: BannerFormat,
) : PositionedBanner {

    private var rotation = 0
    private var positionState: PositionState = PositionState.Default
    private var publisherListener: BannerListener? = null
    private val cache: BannersCache by lazy {
        get {
            params(format)
        }
    }

    private val adRenderer: AdRenderer by lazy { get() }

    private val bannerView by lazy {
        BannerView(activity)
    }

    override fun setPosition(position: BannerPosition) {
        positionState = PositionState.Place(position)
        render(bannerView, positionState)
    }

    override fun setPosition(left: Int, top: Int) {
        positionState = PositionState.Coordinate(left, top)
        render(bannerView, positionState)
    }

    override fun setRotation(degree: Int) {
        rotation = 0
        render(bannerView, positionState)
    }

    override fun showAd(pricefloor: Double) {
        if (!BidonSdk.isInitialized()) {
            publisherListener?.onAdLoadFailed(BidonError.SdkNotInitialized)
            return
        }
        cache.load(
            activity = activity,
            pricefloor = pricefloor,
            onLoaded = { bannerView ->
                render(bannerView, positionState)
            },
            onFailed = {
                publisherListener?.onAdLoadFailed(it)
            }
        )
    }

    override fun hideAd() {
        adRenderer.hide()
    }

    override fun setBannerListener(listener: BannerListener?) {
        publisherListener = listener
        bannerView.setBannerListener(publisherListener)
    }

    private fun render(bannerView: BannerView, positionState: PositionState) {
        when (positionState) {
            is PositionState.Coordinate -> TODO()
            is PositionState.Place -> {
                adRenderer.render(
                    activity = activity,
                    adView = bannerView,
                    position = positionState.position,
                    animate = true,
                    isRotated = positionState.position in arrayOf(BannerPosition.MiddleLeft, BannerPosition.MiddleRight),
                    handleConfigurationChanges = false,
                    format = format,
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

    internal sealed interface PositionState {
        data class Place(val position: BannerPosition) : PositionState
        data class Coordinate(val left: Int, val top: Int) : PositionState

        companion object {
            val Default get() = Place(BannerPosition.BottomCenter)
        }
    }
}
