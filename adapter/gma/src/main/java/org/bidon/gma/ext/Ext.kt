package org.bidon.gma.ext

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import org.bidon.gma.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo.isTablet

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion: String
    get() = try {
        MobileAds.getVersion().toString()
    } catch (e: Throwable) {
        "1.0.0"
    }
    set(_) {}

internal fun BannerFormat.toGmaAdSize(
    context: Context,
    containerWidth: Float
): AdSize {
    return when (this) {
        BannerFormat.Banner -> AdSize.BANNER
        BannerFormat.LeaderBoard -> AdSize.LEADERBOARD
        BannerFormat.MRec -> AdSize.MEDIUM_RECTANGLE
        BannerFormat.Adaptive -> {
            if (isTablet) {
                AdSize.LEADERBOARD
            } else {
                AdSize.BANNER
            }
            // TODO(): Fix this — future adaptive banner support
        }
    }
}
