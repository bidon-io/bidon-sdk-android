package org.bidon.mytarget.ext

import com.my.target.ads.MyTargetView.AdSize
import com.my.target.common.MyTargetVersion
import com.my.target.common.models.IAdLoadingError
import com.my.target.common.models.IAdLoadingError.LoadErrorType.INTERNAL_SERVER_ERROR
import com.my.target.common.models.IAdLoadingError.LoadErrorType.INVALID_BANNER_TYPE
import com.my.target.common.models.IAdLoadingError.LoadErrorType.INVALID_URL
import com.my.target.common.models.IAdLoadingError.LoadErrorType.NETWORK_CONNECTION_FAILED
import com.my.target.common.models.IAdLoadingError.LoadErrorType.REQUEST_TIMEOUT
import com.my.target.common.models.IAdLoadingError.LoadErrorType.REQUIRED_FIELD_MISSED
import com.my.target.common.models.IAdLoadingError.LoadErrorType.UNDEFINED_DATA_ERROR
import com.my.target.common.models.IAdLoadingError.LoadErrorType.UNDEFINED_PARSE_ERROR
import org.bidon.mytarget.MyTargetDemandId
import org.bidon.sdk.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.config.BidonError

internal const val adapterVersion = BuildConfig.ADAPTER_VERSION
internal val sdkVersion = MyTargetVersion.VERSION

internal fun BannerFormat.toAdSize() =
    when (this) {
        BannerFormat.LeaderBoard -> AdSize.ADSIZE_728x90
        BannerFormat.MRec -> AdSize.ADSIZE_300x250
        else -> AdSize.ADSIZE_320x50
    }

internal fun IAdLoadingError.asBidonError(bannerFormat: BannerFormat? = null): BidonError {
    return when (this.code) {
        NETWORK_CONNECTION_FAILED -> BidonError.NetworkError(MyTargetDemandId)
        REQUEST_TIMEOUT -> BidonError.FillTimedOut(MyTargetDemandId)
        INVALID_URL,
        INTERNAL_SERVER_ERROR,
        UNDEFINED_DATA_ERROR,
        UNDEFINED_PARSE_ERROR -> BidonError.Unspecified(MyTargetDemandId, Throwable(message))

        REQUIRED_FIELD_MISSED -> BidonError.IncorrectAdUnit(MyTargetDemandId, message)
        INVALID_BANNER_TYPE -> bannerFormat?.let {
            BidonError.AdFormatIsNotSupported(MyTargetDemandId.demandId, it)
        } ?: BidonError.Unspecified(MyTargetDemandId, Throwable(message))

        else -> BidonError.NoFill(MyTargetDemandId)
    }
}