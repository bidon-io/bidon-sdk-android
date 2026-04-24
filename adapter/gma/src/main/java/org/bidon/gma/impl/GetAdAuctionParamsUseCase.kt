package org.bidon.gma.impl

import org.bidon.gma.GmaBannerAuctionParams
import org.bidon.gma.GmaFullscreenAdAuctionParams
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.ads.AdType

internal class GetAdAuctionParamsUseCase {
    operator fun invoke(
        auctionParamsScope: AdAuctionParamSource,
        adType: AdType
    ): Result<AdAuctionParams> {
        return auctionParamsScope {
            when (adType) {
                AdType.Banner -> {
                    GmaBannerAuctionParams.Network(
                        activity = activity,
                        bannerFormat = bannerFormat,
                        containerWidth = containerWidth,
                        adUnit = adUnit,
                    )
                }

                AdType.Interstitial, AdType.Rewarded -> {
                    GmaFullscreenAdAuctionParams.Network(
                        activity = activity,
                        adUnit = adUnit,
                    )
                }
            }
        }
    }
}
