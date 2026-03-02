package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.stats.StatisticsCollector

internal fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType =
    when (this) {
        is AdTypeParam.Banner -> {
            StatisticsCollector.AdType.Banner(
                when (bannerFormat) {
                    BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                    BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                    BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                    BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                }
            )
        }

        is AdTypeParam.Interstitial -> {
            StatisticsCollector.AdType.Interstitial
        }

        is AdTypeParam.Rewarded -> {
            StatisticsCollector.AdType.Rewarded
        }
    }