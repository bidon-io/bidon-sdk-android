package org.bidon.yandex

import com.yandex.mobile.ads.common.BidderTokenLoadListener
import com.yandex.mobile.ads.common.BidderTokenLoader
import com.yandex.mobile.ads.common.BidderTokenRequest
import com.yandex.mobile.ads.common.YandexAds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.yandex.ext.toYandexBannerSize
import kotlin.coroutines.resume

internal class GetYandexTokenUseCase {
    @Suppress("DEPRECATION")
    suspend operator fun invoke(adTypeParam: AdTypeParam): String? =
        suspendCancellableCoroutine { continuation ->
            val context = adTypeParam.activity.applicationContext

            val requestParameters = mapOf(
                "adapter_network_name" to "Bidon",
                "adapter_version" to YandexAds.libraryVersion,
                "adapter_network_sdk_version" to BidonSdk.SdkVersion
            )

            val bidderTokenRequest = when (adTypeParam) {
                is AdTypeParam.Banner -> {
                    val bannerAdSize = adTypeParam.bannerFormat.toYandexBannerSize(context)
                    BidderTokenRequest.banner(bannerAdSize, null, requestParameters)
                }
                is AdTypeParam.Interstitial -> {
                    BidderTokenRequest.interstitial(null, requestParameters)
                }
                is AdTypeParam.Rewarded -> {
                    BidderTokenRequest.rewarded(null, requestParameters)
                }
            }

            val loader = BidderTokenLoader(context)
            loader.loadBidderToken(
                request = bidderTokenRequest,
                listener = object : BidderTokenLoadListener {
                    override fun onBidderTokenLoaded(bidderToken: String) {
                        logInfo(TAG, "Loaded bidder token for ${adTypeParam::class.simpleName}")
                        continuation.resume(bidderToken)
                    }

                    override fun onBidderTokenFailedToLoad(failureReason: String) {
                        logError(TAG, "Error while loading bidder token for ${adTypeParam::class.simpleName}: $failureReason", BidonError.NoBid)
                        continuation.resume(null)
                    }
                })
        }
}

private const val TAG = "GetYandexTokenUseCase"
