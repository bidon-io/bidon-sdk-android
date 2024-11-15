package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider.SortStrategy.MAX_ECPM
import org.bidon.sdk.cache.AdCacheSettingsProvider.SortStrategy.TIMESTAMP
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Created by Bidon Team on 14/11/2024.
 *
 * Implementation of [AdCacheProvider].
 */
internal class AdCacheProviderImpl(
    private val settings: AdCacheSettingsProvider
) : AdCacheProvider {

    private val adCacheInstances = mutableMapOf<AdCacheKey, AdCache>()

    override fun provide(
        demandAd: DemandAd,
        bannerFormat: BannerFormat // only for banner ad cache
    ): AdCache {
        return when (demandAd.adType) {
            AdType.Banner -> provideBannerCache(demandAd, bannerFormat)
            AdType.Interstitial -> provideInterstitialCache(demandAd)
            AdType.Rewarded -> provideRewardedCache(demandAd)
        }
    }

    private fun provideInterstitialCache(demandAd: DemandAd): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(demandAd.adType)) {
            createAdCache(demandAd)
        }
    }

    private fun provideRewardedCache(demandAd: DemandAd): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(demandAd.adType)) {
            createAdCache(demandAd)
        }
    }

    private fun provideBannerCache(demandAd: DemandAd, bannerFormat: BannerFormat): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(demandAd.adType, bannerFormat)) {
            createAdCache(demandAd, bannerFormat)
        }
    }

    private fun createAdCache(demandAd: DemandAd, bannerFormat: BannerFormat? = null): AdCache {
        logInfo(TAG, "Create ad cache for ${demandAd.adType.code}${if (bannerFormat != null) ", format - $bannerFormat" else ""}")

        val adCacheSettings = when (demandAd.adType) {
            AdType.Banner -> settings.settings.banner
            AdType.Interstitial -> settings.settings.interstitial
            AdType.Rewarded -> settings.settings.rewardedVideo
        }

        val adCacheSorter = when (adCacheSettings.sortStrategy) {
            MAX_ECPM -> AdCacheSorter.MaxEcpm
            TIMESTAMP -> AdCacheSorter.Timestamp
        }

        // TODO: 15/11/2024 [glavatskikh] DI needed?
        return AdCacheImpl(
            demandAd = demandAd,
            adCacheSorter = adCacheSorter,
            adCacheSettings = adCacheSettings,
            scope = CoroutineScope(SdkDispatchers.Main),
        )
    }

    private data class AdCacheKey(val adType: AdType, val bannerFormat: BannerFormat? = null)
}

private const val TAG = "AdCacheProvider"
