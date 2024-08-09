package org.bidon.chartboost.ext

import com.chartboost.sdk.Chartboost
import com.chartboost.sdk.events.CacheError
import com.chartboost.sdk.events.ShowError
import org.bidon.chartboost.BuildConfig
import org.bidon.sdk.config.BidonError

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = Chartboost.getSDKVersion()

internal fun CacheError?.asBidonCacheError(): BidonError = when (this?.code) {
//    CacheError.Code.INTERNAL
//    CacheError.Code.INTERNET_UNAVAILABLE,
//    CacheError.Code.NETWORK_FAILURE,
//    CacheError.Code.NO_AD_FOUND,
//    CacheError.Code.SESSION_NOT_STARTED,
//    CacheError.Code.SERVER_ERROR,
//    CacheError.Code.ASSET_DOWNLOAD_FAILURE,
//    CacheError.Code.BANNER_DISABLED,
//    CacheError.Code.BANNER_VIEW_IS_DETACHED;
    else -> TODO() // BidonError.Unspecified(ChartboostDemandId)
}

internal fun ShowError?.asBidonShowError(): BidonError = when (this?.code) {
    else -> TODO() // BidonError.Unspecified(ChartboostDemandId)
}