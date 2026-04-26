package org.bidon.gma

import com.google.android.libraries.ads.mobile.sdk.common.AdLoadError
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenAdError
import org.bidon.sdk.config.BidonError

/**
 * Maps GMA Next-Gen SDK [AdLoadError] to a [BidonError].
 *
 * Error codes reference:
 * https://developers.google.com/android/reference/com/google/android/libraries/ads/mobile/sdk/common/AdLoadError
 */
internal fun AdLoadError.asBidonError(): BidonError = when (this.code) {
    AdLoadError.Code.NO_FILL -> BidonError.NoFill(GmaDemandId)
    AdLoadError.Code.NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Domain: ${this.domain}. Message: ${this.message}. Code: ${this.code}")
    )
}

/**
 * Maps GMA Next-Gen SDK [FullScreenAdError] to a [BidonError].
 */
internal fun FullScreenAdError.asBidonError(): BidonError = when (this.code) {
    FullScreenAdError.Code.NOT_READY -> BidonError.AdNotReady
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Domain: ${this.domain}. Message: ${this.message}. Code: ${this.code}")
    )
}
