package org.bidon.zmaticoo.ext

import com.zmaticoo.sdk.base.common.ZMaticooAds
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.config.BidonError
import org.bidon.zmaticoo.BuildConfig
import org.bidon.zmaticoo.ZmaticooDemandId

/**
 * Created by Bidon Team on 09/01/2026.
 */
internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = ZMaticooAds.SDK_VERSION

internal fun ComponentError?.asBidonError(): BidonError = when (this?.errorCode) {
    ComponentError.LOAD_FAILED_SDK_NOT_INITIALIZED,
    ComponentError.SHOW_FAILED_SDK_NOT_INITIALIZED -> BidonError.SdkNotInitialized

    ComponentError.LOAD_FAILED_EMPTY_UNIT_ID -> BidonError.NoAppropriateAdUnitId

    ComponentError.LOAD_FAILED_TIME_OUT -> BidonError.BidTimedOut(ZmaticooDemandId)

    ComponentError.LOAD_FAILED_NO_FILL -> BidonError.NoFill(ZmaticooDemandId)

    ComponentError.LOAD_FAILED_NETWORK_ERROR,
    ComponentError.SHOW_FAILED_NETWORK_UNREACHABLE -> BidonError.NetworkError(ZmaticooDemandId)

    ComponentError.LOAD_FAILED_COMPONENT_TIME_OUT -> BidonError.FillTimedOut(ZmaticooDemandId)

    ComponentError.SHOW_FAILED_AD_IS_SHOWING,
    ComponentError.SHOW_FAILED_AD_NOT_READY,
    ComponentError.SHOW_FAILED_AD_NOT_IS_READY,
    ComponentError.SHOW_FAILED_INTERNAL_ERROR,
    ComponentError.SHOW_FAILED_AD_INVALID,
    ComponentError.SHOW_FAILED_RENDER_FAIL,
    ComponentError.SHOW_FAILED_DISPLAY_FAILED -> BidonError.AdNotReady

    null -> BidonError.Unspecified(
        demandId = ZmaticooDemandId,
        cause = Throwable("Unknown Zmaticoo error")
    )
    else -> BidonError.Unspecified(
        demandId = ZmaticooDemandId,
        cause = Throwable(this.message)
    )
}
