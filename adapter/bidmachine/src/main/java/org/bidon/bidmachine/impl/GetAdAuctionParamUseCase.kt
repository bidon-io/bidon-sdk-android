package org.bidon.bidmachine.impl

import org.bidon.bidmachine.BMBannerAuctionParams
import org.bidon.bidmachine.BMFullscreenAuctionParams
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.stats.models.BidType

/**
 * Created by Aleksei Cherniaev on 21/11/2023.
 */
class GetAdAuctionParamUseCase {
    fun getBMFullscreenAuctionParams(auctionParamsScope: AdAuctionParamSource): Result<BMFullscreenAuctionParams> {
        return auctionParamsScope {
            val bidType = auctionParamsScope.adUnit.bidType
            BMFullscreenAuctionParams(
                price = adUnit.pricefloor,
                timeout = timeout,
                context = activity.applicationContext,
                adUnit = adUnit,
                payload = if (bidType == BidType.RTB) {
                    adUnit.extra?.getString("payload")
                } else null
            )
        }
    }

    fun getBMBannerAuctionParams(auctionParamsScope: AdAuctionParamSource): Result<BMBannerAuctionParams> {
        return auctionParamsScope {
            val bidType = auctionParamsScope.adUnit.bidType
            BMBannerAuctionParams(
                price = adUnit.pricefloor,
                timeout = timeout,
                activity = activity,
                bannerFormat = bannerFormat,
                adUnit = adUnit,
                payload = if (bidType == BidType.RTB) {
                    adUnit.extra?.getString("payload")
                } else null
            )
        }
    }
}