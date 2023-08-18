package org.bidon.admob.ext

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.MobileAds
import org.bidon.admob.BuildConfig
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.banner.BannerFormat

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = MobileAds.getVersion().toString()
private const val REQUEST_AGENT = "Bidon" // TODO should

internal fun AdRequest.Builder.bindBiddingParams(): AdRequest.Builder = this.apply {
    val networkExtras = BidonSdk.regulation.asBundle().apply {
        putString("query_info_type", "requester_type_2") // AppLovin MAX, IronSource - "requester_type_2"
//      putString("query_info_type", "requester_type_3") // Chartboost - "requester_type_3"
    }
    setRequestAgent(REQUEST_AGENT)
    addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
}

internal fun AdRequest.Builder.bindFillParams(payload: String): AdRequest.Builder = this.apply {
    val networkExtras = BidonSdk.regulation.asBundle()
    setAdString(payload)
    setRequestAgent(REQUEST_AGENT)
    addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
}

@Suppress("DEPRECATION")
internal fun BannerFormat.toAdmobAdSize(
    context: Context,
    containerWidth: Float
): AdSize {
    return when (this) {
        BannerFormat.Banner -> AdSize.BANNER
        BannerFormat.LeaderBoard -> AdSize.LEADERBOARD
        BannerFormat.MRec -> AdSize.MEDIUM_RECTANGLE
        BannerFormat.Adaptive -> {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val outMetrics = DisplayMetrics()
            display.getMetrics(outMetrics)
            val density = outMetrics.density
            var adWidthPixels = containerWidth
            if (adWidthPixels == 0f) {
                adWidthPixels = outMetrics.widthPixels.toFloat()
            }
            val adWidth = (adWidthPixels / density).toInt()
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
        }
    }
}
