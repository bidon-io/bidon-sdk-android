package org.bidon.gma

import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.bidon.sdk.config.BidonError

/**
 * Maps GMA Next-Gen SDK [LoadAdError] to a [BidonError].
 *
 * Error codes reference:
 * https://developers.google.com/android/reference/com/google/android/libraries/ads/mobile/sdk/common/LoadAdError
 */
internal fun LoadAdError.asBidonError(): BidonError = when (this.code) {
    LoadAdError.ErrorCode.NO_FILL -> BidonError.NoFill(GmaDemandId)
    LoadAdError.ErrorCode.NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Message: ${this.message}. Code: ${this.code}")
    )
}

/**
 * Maps GMA Next-Gen SDK [FullScreenContentError] to a [BidonError].
 */
internal fun FullScreenContentError.asBidonError(): BidonError =
    BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Message: ${this.message}. Code: ${this.code}")
    )
