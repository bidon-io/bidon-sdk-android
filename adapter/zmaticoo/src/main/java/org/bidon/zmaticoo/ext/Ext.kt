package org.bidon.zmaticoo.ext

import com.zmaticoo.sdk.base.common.ZMaticooAds
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.config.BidonError
import org.bidon.zmaticoo.BuildConfig
import org.bidon.zmaticoo.ZmaticooDemandId

/**
 * Created by Vladimir Khrolovich on 09/01/2026.
 */
internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = ZMaticooAds.SDK_VERSION

internal fun ComponentError?.asBidonError(): BidonError = when (this?.errorCode) {
    // Init errors
    ComponentError.INIT_FAILED_INVALID_CONFIGURATION -> BidonError.AppKeyIsInvalid
    ComponentError.INIT_FAILED_INTERNAL_ERROR -> BidonError.InternalServerSdkError("Zmaticoo init failed: internal error")

    // Load errors
    ComponentError.LOAD_FAILED_EMPTY_UNIT_ID -> BidonError.NoAppropriateAdUnitId
    ComponentError.LOAD_FAILED_SDK_NOT_INITIALIZED -> BidonError.SdkNotInitialized
    ComponentError.LOAD_FAILED_IS_LOADING -> BidonError.Unspecified(ZmaticooDemandId, Throwable(this.message))
    ComponentError.LOAD_FAILED_BID_TOKEN_EMPTY -> BidonError.NoBid
    ComponentError.LOAD_FAILED_TIME_OUT -> BidonError.BidTimedOut(ZmaticooDemandId)
    ComponentError.LOAD_FAILED_NO_FILL -> BidonError.NoFill(ZmaticooDemandId)
    ComponentError.LOAD_FAILED_INTERNAL_ERROR -> BidonError.InternalServerSdkError("Zmaticoo load failed: internal error")
    ComponentError.LOAD_FAILED_PLACEMENT_NOT_FOUND -> BidonError.NoAppropriateAdUnitId
    ComponentError.LOAD_FAILED_NETWORK_ERROR -> BidonError.NetworkError(ZmaticooDemandId)
    ComponentError.LOAD_FAILED_FREQUENT_REQUEST_EXCEPTION -> BidonError.Unspecified(ZmaticooDemandId, Throwable(this.message))
    ComponentError.LOAD_FAILED_BLACK_DEVICE -> BidonError.Unspecified(ZmaticooDemandId, Throwable(this.message))
    ComponentError.LOAD_FAILED_COMPONENT_TIME_OUT -> BidonError.FillTimedOut(ZmaticooDemandId)
    ComponentError.LOAD_FAILED_NOT_TRACK_STATUS -> BidonError.Unspecified(ZmaticooDemandId, Throwable(this.message))

    // Show errors
    ComponentError.SHOW_FAILED_AD_IS_SHOWING -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_AD_NOT_READY,
    ComponentError.SHOW_FAILED_AD_NOT_IS_READY -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_INTERNAL_ERROR -> BidonError.InternalServerSdkError("Zmaticoo show failed: internal error")
    ComponentError.SHOW_FAILED_NETWORK_UNREACHABLE -> BidonError.NetworkError(ZmaticooDemandId)
    ComponentError.SHOW_FAILED_MISSING_ACTIVITY -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_AD_INVALID -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_RENDER_FAIL -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_SDK_NOT_INITIALIZED -> BidonError.SdkNotInitialized
    ComponentError.SHOW_FAILED_DISPLAY_FAILED -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_AD_TYPE_INVALID -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_APP_CONTEXT_IS_NULL -> BidonError.AdNotReady
    ComponentError.SHOW_FAILED_APP_LAUNCH_FAILED -> BidonError.AdNotReady

    // WebView errors
    ComponentError.CODE_WEBVIEW_RECEIVED_ERROR,
    ComponentError.CODE_RENDER_PROCESS_GONE,
    ComponentError.CODE_LOAD_WEBVIEW_TIMEOUT,
    ComponentError.CODE_WEBVIEW_RENDER_FAIL,
    ComponentError.CODE_WEBVIEW_UNLOAD -> BidonError.InternalServerSdkError(this.message)

    null -> BidonError.Unspecified(ZmaticooDemandId, Throwable("Unknown Zmaticoo error"))
    else -> BidonError.Unspecified(ZmaticooDemandId, Throwable(this.message))
}
