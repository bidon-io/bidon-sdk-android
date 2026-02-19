package org.bidon.sdk.ads.cache.denis.processors

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.stats.StatisticsCollector

/**
 * Factory for creating and configuring AdSource instances.
 * Shared utility for CPM and RTB processors.
 */
internal object AdSourceFactory {
    /**
     * Create AdSource from adapter based on ad type.
     *
     * @param adapter Adapter instance
     * @param demandAd Demand ad configuration
     * @param adTypeParam Ad type parameters
     * @param tag Tag for logging (caller's TAG)
     * @return AdSource instance or null if adapter doesn't support the ad type
     */
    fun createAdSource(
        adapter: Adapter,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        tag: String,
    ): AdSource<AdAuctionParams>? {
        val adapterDemandId = adapter.demandId
        return when (demandAd.adType) {
            AdType.Interstitial -> {
                (adapter as? AdProvider.Interstitial<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.interstitial().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(tag, "Failed to create interstitial ad source", it)
                    }.getOrNull()
                }
            }
            AdType.Rewarded -> {
                (adapter as? AdProvider.Rewarded<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.rewarded().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(tag, "Failed to create rewarded ad source", it)
                    }.getOrNull()
                }
            }
            AdType.Banner -> {
                (adapter as? AdProvider.Banner<AdAuctionParams>)?.let { provider ->
                    runCatching {
                        provider.banner().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(tag, "Failed to create banner ad source", it)
                    }.getOrNull()
                }
            }
        }
    }

    /**
     * Apply auction parameters to AdSource.
     *
     * @param adSource AdSource instance
     * @param auctionId Auction identifier
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param demandAd Demand ad configuration
     * @param pricefloor Minimum acceptable price
     * @param adTypeParam Ad type parameters
     */
    fun applyParams(
        adSource: AdSource<AdAuctionParams>,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        demandAd: DemandAd,
        pricefloor: Double,
        adTypeParam: AdTypeParam,
    ) {
        // Set statistic ad type (CRITICAL: must be set before show)
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())

        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = pricefloor,
        )
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }
}

/**
 * Convert AdTypeParam to StatisticsCollector.AdType.
 */
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
