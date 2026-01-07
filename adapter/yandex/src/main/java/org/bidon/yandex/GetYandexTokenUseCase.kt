package org.bidon.yandex

import com.yandex.mobile.ads.common.AdType
import com.yandex.mobile.ads.common.BidderTokenLoadListener
import com.yandex.mobile.ads.common.BidderTokenLoader
import com.yandex.mobile.ads.common.BidderTokenRequestConfiguration
import com.yandex.mobile.ads.common.MobileAds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.yandex.ext.toYandexBannerSize
import kotlin.coroutines.resume

internal class GetYandexTokenUseCase {
    suspend operator fun invoke(adTypeParam: AdTypeParam): String? =
        suspendCancellableCoroutine { continuation ->
            val context = adTypeParam.activity.applicationContext

            val requestParameters = mapOf(
                "adapter_network_name" to "Bidon",
                "adapter_version" to MobileAds.libraryVersion,
                "adapter_network_sdk_version" to BidonSdk.SdkVersion
            )

            val requestConfiguration = when (adTypeParam) {
                is AdTypeParam.Banner -> {
                    val bannerAdSize = adTypeParam.bannerFormat.toYandexBannerSize(context)
                    BidderTokenRequestConfiguration.banner(bannerAdSize, requestParameters)
                }
                is AdTypeParam.Interstitial -> {
                    BidderTokenRequestConfiguration.interstitial(requestParameters)
                }
                is AdTypeParam.Rewarded -> {
                    BidderTokenRequestConfiguration.rewarded(requestParameters)
                }
            }

            val adTypeForLogging = when (adTypeParam) {
                is AdTypeParam.Banner -> AdType.BANNER
                is AdTypeParam.Interstitial -> AdType.INTERSTITIAL
                is AdTypeParam.Rewarded -> AdType.REWARDED
            }

            BidderTokenLoader.loadBidderToken(
                context = context,
                bidderTokenRequestConfiguration = requestConfiguration,
                listener = object : BidderTokenLoadListener {
                    override fun onBidderTokenLoaded(bidderToken: String) {
                        logInfo(TAG, "Loaded bidder token for $adTypeForLogging")
                        continuation.resume(bidderToken)
                    }

                    override fun onBidderTokenFailedToLoad(failureReason: String) {
                        logError(TAG, "Error while loading bidder token for $adTypeForLogging: $failureReason", BidonError.NoBid)
                        continuation.resume(null)
                    }
                })
        }
}

private const val TAG = "GetYandexTokenUseCase"
