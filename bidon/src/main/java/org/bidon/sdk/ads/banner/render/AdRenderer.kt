package org.bidon.sdk.ads.banner.render

import android.app.Activity
import android.view.View
import org.bidon.sdk.ads.banner.BannerPosition
import org.bidon.sdk.ads.banner.BannerView

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 */
internal interface AdRenderer {
    fun render(
        activity: Activity,
        bannerView: BannerView,
        position: BannerPosition,
        useSafeArea: Boolean,
        animate: Boolean,
        isRotated: Boolean,
        handleConfigurationChanges: Boolean,
        renderListener: RenderListener
    ): Boolean

    fun hide()

    interface RenderListener {
        fun onRendered()
        fun onRenderFailed()
        fun onVisibilityIssued()
    }

    interface RenderInspector {
        fun isRenderPermitted(): Boolean
        fun isActivityValid(activity: Activity): Boolean
        fun isViewVisibleOnScreen(view: View?): Boolean
    }
}