package org.bidon.gma

import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.bidon.sdk.config.BidonError

// GMA Next-Gen SDK error codes
private const val ERROR_CODE_INTERNAL_ERROR = 0
private const val ERROR_CODE_INVALID_REQUEST = 1
private const val ERROR_CODE_NETWORK_ERROR = 2
private const val ERROR_CODE_NO_FILL = 3

internal fun LoadAdError.asBidonError(): BidonError = when (this.code) {
    ERROR_CODE_NO_FILL -> BidonError.NoFill(GmaDemandId)
    ERROR_CODE_NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Domain: $domain. Message: $message. Code: $code")
    )
}

internal fun FullScreenContentError.asBidonError(): BidonError = when (this.code) {
    ERROR_CODE_NO_FILL -> BidonError.NoFill(GmaDemandId)
    ERROR_CODE_NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Message: $message. Code: $code")
    )
}
