package org.bidon.gma

import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest.ERROR_CODE_MEDIATION_NO_FILL
import com.google.android.gms.ads.AdRequest.ERROR_CODE_NETWORK_ERROR
import com.google.android.gms.ads.AdRequest.ERROR_CODE_NO_FILL
import com.google.android.gms.ads.LoadAdError
import org.bidon.sdk.config.BidonError

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
