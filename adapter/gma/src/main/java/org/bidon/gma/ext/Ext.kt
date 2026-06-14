package org.bidon.gma.ext

import com.google.android.libraries.ads.mobile.sdk.common.AdSize
import org.bidon.gma.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo.isTablet

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = BuildConfig.ADAPTER_VERSION.substringBeforeLast(".")

internal fun BannerFormat.toGmaAdSize(): AdSize {
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
        }
    }
}
