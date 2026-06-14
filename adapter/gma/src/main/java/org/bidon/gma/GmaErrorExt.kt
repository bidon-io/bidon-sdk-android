package org.bidon.gma

import com.google.android.libraries.ads.mobile.sdk.common.AdError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.bidon.sdk.config.BidonError

/**
 * Error code constants for GMA Next-Gen SDK.
 * These mirror the legacy GMS SDK error codes.
 */
private const val ERROR_CODE_NO_FILL = 3
private const val ERROR_CODE_NETWORK_ERROR = 2
private const val ERROR_CODE_MEDIATION_NO_FILL = 9

internal fun LoadAdError.asBidonError(): BidonError = when (this.code) {
    ERROR_CODE_NO_FILL,
    ERROR_CODE_MEDIATION_NO_FILL -> BidonError.NoFill(GmaDemandId)
    ERROR_CODE_NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Domain: $domain. Message: $message. Code: $code")
    )
}

internal fun AdError.asBidonError(): BidonError = when (this.code) {
    ERROR_CODE_NO_FILL,
    ERROR_CODE_MEDIATION_NO_FILL -> BidonError.NoFill(GmaDemandId)
    ERROR_CODE_NETWORK_ERROR -> BidonError.NetworkError(GmaDemandId)
    else -> BidonError.Unspecified(
        demandId = GmaDemandId,
        cause = Throwable("Domain: $domain. Message: $message. Code: $code")
    )
}
