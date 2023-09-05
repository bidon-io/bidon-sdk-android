package org.bidon.sdk.ads.banner.render

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerPosition

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 */
internal interface AdRenderer {
    fun render(
        activity: Activity,
        adView: ViewGroup,
        position: BannerPosition,
        useSafeArea: Boolean,
        animate: Boolean,
        isRotated: Boolean,
        handleConfigurationChanges: Boolean,
        format: BannerFormat,
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