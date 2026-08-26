package org.bidon.moloco.ext

import com.moloco.sdk.publisher.Banner
import com.moloco.sdk.publisher.BannerAdSize
import com.moloco.sdk.publisher.MediationInfo
import com.moloco.sdk.publisher.Moloco
import com.moloco.sdk.publisher.MolocoAdError
import org.bidon.moloco.EMPTY_MEDIATOR
import org.bidon.moloco.EMPTY_WATERMARK
import org.bidon.moloco.MolocoDemandId
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.config.BidonError

internal fun MolocoAdError.toBidonLoadError() = when (errorType) {
    MolocoAdError.ErrorType.AD_LOAD_FAILED_SDK_NOT_INIT,
    MolocoAdError.ErrorType.SDK_INIT_ERROR -> BidonError.SdkNotInitialized

    MolocoAdError.ErrorType.AD_LOAD_FAILED -> BidonError.NoFill(MolocoDemandId)
    MolocoAdError.ErrorType.AD_LOAD_TIMEOUT_ERROR -> BidonError.FillTimedOut(MolocoDemandId)
    MolocoAdError.ErrorType.AD_LOAD_BID_FAILED -> BidonError.NoFill(MolocoDemandId)
    else -> BidonError.Unspecified(MolocoDemandId, message = this.description)
}

internal fun MolocoAdError.toBidonShowError() = when (errorType) {
    MolocoAdError.ErrorType.AD_SHOW_ERROR_NOT_LOADED -> BidonError.AdNotReady
    else -> BidonError.Unspecified(MolocoDemandId, message = this.description)
}

internal fun Moloco.createBannerAd(
    bannerFormat: BannerFormat,
    adUnitId: String,
    callback: (Banner?, Throwable?) -> Unit
) {
    val sdkCallback: (Banner?, MolocoAdError.AdCreateError?) -> Unit = { banner, err ->
        if (banner != null) {
            callback(banner, null)
        } else {
            val exception = Exception(
                "${bannerFormat.name} wasn't created. " +
                    "Error: ${err?.description}, code: ${err?.errorCode}"
            )
            callback(null, exception)
        }
    }

    try {
        when (bannerFormat) {
            BannerFormat.Banner -> {
                Moloco.createBanner(
                    mediationInfo = MediationInfo(EMPTY_MEDIATOR),
                    adUnitId = adUnitId,
                    watermarkString = EMPTY_WATERMARK,
                    callback = sdkCallback
                )
            }

            BannerFormat.LeaderBoard -> {
                Moloco.createBannerTablet(
                    mediationInfo = MediationInfo(EMPTY_MEDIATOR),
                    adUnitId = adUnitId,
                    watermarkString = EMPTY_WATERMARK,
                    callback = sdkCallback
                )
            }

            BannerFormat.MRec -> {
                Moloco.createMREC(
                    mediationInfo = MediationInfo(EMPTY_MEDIATOR),
                    adUnitId = adUnitId,
                    watermarkString = EMPTY_WATERMARK,
                    callback = sdkCallback
                )
            }

            BannerFormat.Adaptive -> {
                Moloco.createMolocoBanner(
                    mediationInfo = MediationInfo(EMPTY_MEDIATOR),
                    adUnitId = adUnitId,
                    // null width lets Moloco auto-detect the full screen width
                    size = BannerAdSize.AnchoredAdaptive(DeviceInfo.screenWidthDp.takeIf { it > 0 }),
                    watermarkString = EMPTY_WATERMARK,
                    callback = sdkCallback
                )
            }
        }
    } catch (t: Throwable) {
        callback(null, t)
    }
}
