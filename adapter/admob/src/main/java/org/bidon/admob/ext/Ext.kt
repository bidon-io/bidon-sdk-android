package org.bidon.admob.ext

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdFormat
import com.google.android.gms.ads.AdFormat.*
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.MobileAds
import org.bidon.admob.BuildConfig
import org.bidon.sdk.BidonSdk

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = MobileAds.getVersion()
private const val REQUEST_AGENT = "Bidon"

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

internal fun AdRequest.Builder.bindBiddingParams() {
    val networkExtras = BidonSdk.regulation.asBundle().apply {
        // TODO chartboost set "requester_type_3", MAX, IS - "requester_type_2"
        putString("query_info_type", "requester_type_2")
    }
    setRequestAgent(REQUEST_AGENT)
    addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
}

internal fun AdRequest.Builder.bindFillParams(bidResponse: String?, adUnitId: String?) {
    val networkExtras = BidonSdk.regulation.asBundle().apply {
        putString("placement_req_id", requireNotNull(adUnitId) {
            "AdUnitId is required for GoogleBidding"
        })
    }
    setAdString(requireNotNull(bidResponse) {
        "Payload is required for GoogleBidding"
    })
    setRequestAgent(REQUEST_AGENT)
    addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
}