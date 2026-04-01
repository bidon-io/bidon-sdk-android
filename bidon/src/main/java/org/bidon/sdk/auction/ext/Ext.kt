package org.bidon.sdk.auction.ext

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector

internal fun AuctionResponse.printWaterfall(adType: AdType) {
    adUnits?.joinToString(separator = "\n") { adUnit ->
        "#${adUnits.indexOf(adUnit)} $adUnit"
    }?.let {
        logInfo("$adType auction waterfall", "\n$it")
    }
}

internal fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType {
    return when (this) {
        is AdTypeParam.Banner -> {
            StatisticsCollector.AdType.Banner(
                format = when (bannerFormat) {
                    BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                    BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                    BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                    BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                }
            )
        }
        is AdTypeParam.Interstitial -> StatisticsCollector.AdType.Interstitial
        is AdTypeParam.Rewarded -> StatisticsCollector.AdType.Rewarded
    }
}
