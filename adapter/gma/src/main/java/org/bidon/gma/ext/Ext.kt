package org.bidon.gma.ext

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize as GmaAdSize
import org.bidon.gma.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo.isTablet

internal var adapterVersion = BuildConfig.ADAPTER_VERSION

// GMA Next-Gen SDK does not expose a public getVersion() method; hardcode the version.
internal var sdkVersion = "1.0.0"

internal fun BannerFormat.toGmaAdSize(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") containerWidth: Float
): GmaAdSize {
    return when (this) {
        BannerFormat.Banner -> GmaAdSize.BANNER
        BannerFormat.LeaderBoard -> GmaAdSize.LEADERBOARD
        BannerFormat.MRec -> GmaAdSize.MEDIUM_RECTANGLE
        BannerFormat.Adaptive -> {
            if (isTablet) GmaAdSize.LEADERBOARD
            else GmaAdSize.BANNER
            // TODO: Use AdSize.getLargeAnchoredAdaptiveBannerAdSize() when containerWidth is available
        }
    }
}
