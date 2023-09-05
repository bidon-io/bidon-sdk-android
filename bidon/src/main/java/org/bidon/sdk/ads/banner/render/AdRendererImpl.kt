package org.bidon.sdk.ads.banner.render

import android.app.Activity
import android.content.res.Resources
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerPosition
import org.bidon.sdk.ads.banner.BannerView
import org.bidon.sdk.logs.logging.impl.logInfo
import java.lang.ref.WeakReference

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 *
 * Hierarchy: (Network) AdView -> AdContainer -> RootContainer
 */
internal class AdRendererImpl(
    private val inspector: AdRenderer.RenderInspector
) : AdRenderer {

    private var activity = WeakReference<Activity>(null)

    /**
     * RootContainer is the only one view for every [activity]
     */
    private var rootContainer: FrameLayout? = null

    /**
     * AdContainer changes with [bannerPosition]
     */
    private var adContainer: FrameLayout? = null
    private var bannerPosition: BannerPosition = BannerPosition.BottomCenter
    private val lifecycleObserver by lazy { LifecycleObserver() }

    override fun render(
        activity: Activity,
        bannerView: BannerView,
        position: BannerPosition,
        useSafeArea: Boolean,
        animate: Boolean,
        isRotated: Boolean,
        handleConfigurationChanges: Boolean,
        renderListener: AdRenderer.RenderListener
    ): Boolean {
        observeActivities(activity)
        logInfo(
            tag = Tag,
            message = "--> AdContainer($adContainer), AdView($bannerView), $position, " +
                "${bannerView.format}, useSafeArea($useSafeArea), animate($animate), " +
                "isRotated($isRotated)"
        )
        if (!inspector.isActivityValid(activity)) {
            renderListener.onRenderFailed()
            return false
        }
        if (bannerPosition != position) {
            logInfo(Tag, "Position changed: $bannerPosition -> $position")
            hide()
        }
        return if (inspector.isRenderPermitted()) {
            bannerPosition = position
            this.activity = WeakReference(activity)
            if (!inspector.isViewVisibleOnScreen(view = rootContainer) || activity != this.activity.get()) {
                createRootContainer(activity)
            }
            if (!inspector.isViewVisibleOnScreen(view = adContainer)) {
                createAdContainer(activity, position, useSafeArea, bannerView.format)
            }
            bannerView.showAd()
            adContainer?.addAdView(
                adView = bannerView,
                position = position,
                format = bannerView.format,
                isRotated = isRotated
            )
            setAdViewsVisible(bannerView)
            logInfo(
                Tag,
                "<-- AdContainer($adContainer), AdView($bannerView), BannerPosition($bannerPosition), BannerFormat(${bannerView.format}), useSafeArea($useSafeArea), animate($animate), isRotated($isRotated)"
            )
            renderListener.onRendered()
            true
        } else {
            renderListener.onRenderFailed()
            false
        }
    }

    override fun hide() {
        adContainer?.removeAllViews()
        adContainer = null
    }

    private fun setAdViewsVisible(adView: ViewGroup) {
        adView.visibility = View.VISIBLE
        adContainer?.visibility = View.VISIBLE
        rootContainer?.visibility = View.VISIBLE
        rootContainer?.bringToFront()
        adContainer?.bringToFront()
    }

    private fun createAdContainer(
        activity: Activity,
        position: BannerPosition,
        useSafeArea: Boolean,
        format: BannerFormat
    ) {
        adContainer?.removeAllViews()
        rootContainer?.removeAllViews()
        adContainer = BannerDisplayContainer(
            context = activity,
            isRotated = position in arrayOf(BannerPosition.MiddleLeft, BannerPosition.MiddleRight),
            useSafeArea = useSafeArea
        ).apply {
            this.layoutParams = LayoutParams(MATCH_PARENT, format.getHeight())
        }
        rootContainer?.addView(
            adContainer,
            getAdContainerLayoutParams(
                position = position,
                width = format.getWidth(),
                height = format.getHeight()
            )
        )
    }

    private fun createRootContainer(activity: Activity) {
        adContainer?.removeAllViews()
        rootContainer?.removeAllViews()
        rootContainer = FrameLayout(activity)
        activity.addContentView(rootContainer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun FrameLayout.addAdView(
        adView: ViewGroup,
        position: BannerPosition,
        format: BannerFormat,
        isRotated: Boolean,
    ) {
        val adContainer: FrameLayout = this
        val oldAdView = adContainer.getChildAt(0)
        val isViewsTheSame = oldAdView == adView
        if (isViewsTheSame && bannerPosition == position) {
            logInfo(Tag, "View and position does not changed")
            return
        }
        adContainer.setBackgroundColor(Color.TRANSPARENT)
        adView.rotation = when (position) {
            BannerPosition.TopLeft -> 0f
            BannerPosition.TopCenter -> 0f
            BannerPosition.TopRight -> 0f
            BannerPosition.MiddleLeft -> -90f + (180f.takeIf { isRotated } ?: 0f)
            BannerPosition.MiddleCenter -> 0f
            BannerPosition.MiddleRight -> 90f - (180f.takeIf { isRotated } ?: 0f)
            BannerPosition.BottomLeft -> 0f
            BannerPosition.BottomCenter -> 0f
            BannerPosition.BottomRight -> 0f
        }
        adContainer.addView(adView, LayoutParams(format.getWidth(), format.getHeight(), position.getAdViewGravity()))
        if (!isViewsTheSame) {
            oldAdView?.animate()
                ?.alpha(0.0f)
                ?.setDuration(800)
                ?.withLayer()
                ?.withStartAction {
                    oldAdView.bringToFront()
                }
                ?.withEndAction {
                    adContainer.removeView(oldAdView)
                }
                ?.start()
        }
    }

    private fun getAdContainerLayoutParams(position: BannerPosition, width: Int, height: Int): LayoutParams {
        return when (position) {
            BannerPosition.TopLeft -> LayoutParams(width, height, Gravity.TOP or Gravity.START)
            BannerPosition.TopCenter -> LayoutParams(width, height, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            BannerPosition.TopRight -> LayoutParams(width, height, Gravity.TOP or Gravity.END)
            BannerPosition.MiddleLeft -> LayoutParams(height, width, Gravity.LEFT or Gravity.CENTER_VERTICAL)
            BannerPosition.MiddleCenter -> LayoutParams(height, width, Gravity.CENTER)
            BannerPosition.MiddleRight -> LayoutParams(height, width, Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            BannerPosition.BottomLeft -> LayoutParams(width, height, Gravity.BOTTOM or Gravity.START)
            BannerPosition.BottomCenter -> LayoutParams(width, height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            BannerPosition.BottomRight -> LayoutParams(width, height, Gravity.BOTTOM or Gravity.END)
        }
    }

    private fun observeActivities(activity: Activity) {
        lifecycleObserver.observe(
            applicationContext = activity.applicationContext,
            onActivityDestroyed = { destroyedActivity ->
                if (this@AdRendererImpl.activity.get() == destroyedActivity) {
                    adContainer?.removeAllViews()
                    adContainer = null
                    rootContainer?.removeAllViews()
                    rootContainer = null
                    this@AdRendererImpl.activity = WeakReference(null)
                }
            }
        )
    }

    private val Int.dp: Int
        get() = (this * Resources.getSystem().displayMetrics.density).toInt()

    private fun BannerPosition.getAdViewGravity(): Int {
        return when (this) {
            BannerPosition.TopLeft -> Gravity.TOP or Gravity.START
            BannerPosition.TopCenter -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            BannerPosition.TopRight -> Gravity.TOP or Gravity.END
            BannerPosition.MiddleLeft -> Gravity.START or Gravity.CENTER_VERTICAL
            BannerPosition.MiddleCenter -> Gravity.CENTER or Gravity.CENTER_VERTICAL
            BannerPosition.MiddleRight -> Gravity.END or Gravity.CENTER_VERTICAL
            BannerPosition.BottomLeft -> Gravity.BOTTOM or Gravity.START
            BannerPosition.BottomCenter -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            BannerPosition.BottomRight -> Gravity.BOTTOM or Gravity.END
        }
    }

    private fun BannerFormat.getWidth() = when (this) {
        BannerFormat.MRec -> 300.dp
        BannerFormat.LeaderBoard -> 728.dp
        BannerFormat.Banner -> 320.dp
        BannerFormat.Adaptive -> WRAP_CONTENT
    }

    private fun BannerFormat.getHeight() = when (this) {
        BannerFormat.MRec -> 250.dp
        BannerFormat.LeaderBoard -> 90.dp
        BannerFormat.Banner -> 50.dp
        BannerFormat.Adaptive -> WRAP_CONTENT
    }
}

private const val Tag = "AdRenderer"