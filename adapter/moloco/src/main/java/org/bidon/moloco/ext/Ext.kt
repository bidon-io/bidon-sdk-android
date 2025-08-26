package org.bidon.moloco.ext

import com.moloco.sdk.publisher.MolocoAdError
import org.bidon.moloco.MolocoDemandId
import org.bidon.sdk.config.BidonError

fun MolocoAdError.toBidonLoadError() = when (errorType) {
    MolocoAdError.ErrorType.AD_LOAD_FAILED_SDK_NOT_INIT,
    MolocoAdError.ErrorType.SDK_INIT_ERROR -> BidonError.SdkNotInitialized

    MolocoAdError.ErrorType.AD_LOAD_FAILED -> BidonError.NoFill(MolocoDemandId)
    MolocoAdError.ErrorType.AD_LOAD_TIMEOUT_ERROR -> BidonError.FillTimedOut(MolocoDemandId)
    MolocoAdError.ErrorType.AD_LOAD_BID_FAILED -> BidonError.NoFill(MolocoDemandId)
    else -> BidonError.Unspecified(MolocoDemandId, message = this.description)
}

fun MolocoAdError.toBidonShowError() = when (errorType) {
    MolocoAdError.ErrorType.AD_SHOW_ERROR_NOT_LOADED -> BidonError.AdNotReady
    else -> BidonError.Unspecified(MolocoDemandId, message = this.description)
}