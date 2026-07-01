package org.bidon.unityads.ext

import com.unity3d.ads.UnityAdsError
import org.bidon.sdk.config.BidonError
import org.bidon.unityads.UnityAdsDemandId

/**
 * Created by Bidon Team on 02/03/2023.
 */
internal fun UnityAdsError?.asBidonError() = when (this?.code) {
    null -> BidonError.Unspecified(UnityAdsDemandId)
    CODE_TIMEOUT -> BidonError.BidTimedOut(UnityAdsDemandId)
    CODE_NO_FILL -> BidonError.NoFill(UnityAdsDemandId)
    CODE_NOT_INITIALIZED -> BidonError.SdkNotInitialized
    CODE_INVALID_CONFIGURATION_A, CODE_INVALID_CONFIGURATION_B -> BidonError.NoAppropriateAdUnitId
    else -> BidonError.Unspecified(demandId = UnityAdsDemandId, cause = Throwable(this.message))
}

private const val CODE_TIMEOUT = 2
private const val CODE_NO_FILL = 52100
private const val CODE_NOT_INITIALIZED = 52101
private const val CODE_INVALID_CONFIGURATION_A = 52102
private const val CODE_INVALID_CONFIGURATION_B = 52104
