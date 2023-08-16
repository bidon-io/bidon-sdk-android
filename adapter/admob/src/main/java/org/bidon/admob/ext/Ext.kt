package org.bidon.admob.ext

import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.ads.AdFormat
import com.google.android.gms.ads.AdFormat.*
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.MobileAds
import org.bidon.admob.BuildConfig
import org.bidon.sdk.BidonSdk

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = MobileAds.getVersion()

internal fun AdFormat.isAdView() = this == BANNER
internal fun AdFormat?.isValid() = this == NATIVE || this == null

@Suppress("DEPRECATION")
internal fun Context.adaptiveAdSize(width: Float): AdSize {
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = windowManager.defaultDisplay
    val outMetrics = DisplayMetrics()
    display.getMetrics(outMetrics)
    val density = outMetrics.density
    var adWidthPixels = width
    if (adWidthPixels == 0f) {
        adWidthPixels = outMetrics.widthPixels.toFloat()
    }
    val adWidth = (adWidthPixels / density).toInt()
    return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth)
}

internal fun getDefaultBiddingParams(): Bundle {
    val bundle = BidonSdk.regulation.asBundle()
    // TODO chartboost set "requester_type_3", MAX, IS - "requester_type_2"
    bundle.putString("query_info_type", "requester_type_2")
    return bundle
}
