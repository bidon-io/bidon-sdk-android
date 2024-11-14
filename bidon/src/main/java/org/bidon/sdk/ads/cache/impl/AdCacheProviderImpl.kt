package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheProvider
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get

/**
 * Created by Bidon Team on 14/11/2024.
 *
 * Implementation of [AdCacheProvider].
 */
internal class AdCacheProviderImpl : AdCacheProvider {

    private val adCacheInstances = mutableMapOf<AdCacheKey, AdCache>()

    override fun provide(demandAd: DemandAd, bannerFormat: BannerFormat?): AdCache {
        return when (demandAd.adType) {
            AdType.Banner -> provideBannerCache(bannerFormat ?: BannerFormat.Banner)
            AdType.Interstitial -> provideInterstitialCache()
            AdType.Rewarded -> provideRewardedCache()
        }
    }

    private fun provideInterstitialCache(): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(AdType.Interstitial)) {
            createAdCache(AdType.Interstitial)
        }
    }

    private fun provideRewardedCache(): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(AdType.Rewarded)) {
            createAdCache(AdType.Rewarded)
        }
    }

    private fun provideBannerCache(bannerFormat: BannerFormat): AdCache {
        return adCacheInstances.getOrPut(AdCacheKey(AdType.Banner, bannerFormat)) {
            createAdCache(AdType.Banner, bannerFormat)
        }
    }

    private fun createAdCache(adType: AdType, bannerFormat: BannerFormat? = null): AdCache {
        logInfo(TAG, "Create ad cache for $adType${if (bannerFormat != null) ", format - $bannerFormat" else ""}")
        return get<AdCache> { params(adType) }
    }

    private data class AdCacheKey(val adType: AdType, val bannerFormat: BannerFormat? = null)
}

private const val TAG = "AdCacheProvider"
