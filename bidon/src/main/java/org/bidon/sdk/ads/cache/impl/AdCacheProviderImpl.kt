package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider
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

    private val adCacheInstances = MutableStateFlow<Map<AdCacheKey, AdCache>>(emptyMap())

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
        return getOrCreateCache(AdCacheKey(demandAd.adType)) {
            createAdCache(demandAd)
        }
    }

    private fun provideRewardedCache(demandAd: DemandAd): AdCache {
        return getOrCreateCache(AdCacheKey(demandAd.adType)) {
            createAdCache(demandAd)
        }
    }

    private fun provideBannerCache(demandAd: DemandAd, bannerFormat: BannerFormat): AdCache {
        return getOrCreateCache(AdCacheKey(demandAd.adType, bannerFormat)) {
            createAdCache(demandAd, bannerFormat)
        }
    }

    private fun getOrCreateCache(key: AdCacheKey, createCache: () -> AdCache): AdCache {
        return adCacheInstances.updateAndGet { currentCaches ->
            if (key in currentCaches) {
                currentCaches
            } else {
                val newCache = createCache()
                currentCaches + (key to newCache)
            }
        }[key] ?: error("Cache should exist after updateAndGet")
    }

    private fun createAdCache(demandAd: DemandAd, bannerFormat: BannerFormat? = null): AdCache {
        logInfo(TAG, "Create ad cache for ${demandAd.adType.code}${if (bannerFormat != null) ", format - $bannerFormat" else ""}")

        val adCacheSettings = when (demandAd.adType) {
            AdType.Banner -> settings.settings.banner
            AdType.Interstitial -> settings.settings.interstitial
            AdType.Rewarded -> settings.settings.rewardedVideo
        }

        return AdCacheImpl(
            demandAd = demandAd,
            settings = adCacheSettings,
            sorter = MaxEcpmAdCacheSorter(),
            scope = CoroutineScope(SdkDispatchers.Main),
        )
    }

    private data class AdCacheKey(val adType: AdType, val bannerFormat: BannerFormat? = null)
}

private const val TAG = "AdCacheProvider"
