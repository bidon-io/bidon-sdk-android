package org.bidon.yandex

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

            val adTypeString = when (adTypeParam) {
                is AdTypeParam.Banner -> "BANNER"
                is AdTypeParam.Interstitial -> "INTERSTITIAL"
                is AdTypeParam.Rewarded -> "REWARDED"
            }

            val requestConfiguration = when (adTypeParam) {
                is AdTypeParam.Banner -> {
                    val bannerAdSize = adTypeParam.bannerFormat.toYandexBannerSize(context)
                    BidderTokenRequestConfiguration.Builder.forBanner(bannerAdSize)
                        .setParameters(requestParameters)
                        .build()
                }
                is AdTypeParam.Interstitial -> {
                    BidderTokenRequestConfiguration.Builder.forInterstitial()
                        .setParameters(requestParameters)
                        .build()
                }
                is AdTypeParam.Rewarded -> {
                    BidderTokenRequestConfiguration.Builder.forRewarded()
                        .setParameters(requestParameters)
                        .build()
                }
            }

            BidderTokenLoader.loadBidderToken(
                context = context,
                bidderTokenRequestConfiguration = requestConfiguration,
                listener = object : BidderTokenLoadListener {
                    override fun onBidderTokenLoaded(bidderToken: String) {
                        logInfo(TAG, "Loaded bidder token for $adTypeString")
                        continuation.resume(bidderToken)
                    }

                    override fun onBidderTokenFailedToLoad(failureReason: String) {
                        logError(TAG, "Error while loading bidder token for $adTypeString: $failureReason", BidonError.NoBid)
                        continuation.resume(null)
                    }
                })
        }
}

private const val TAG = "GetYandexTokenUseCase"
