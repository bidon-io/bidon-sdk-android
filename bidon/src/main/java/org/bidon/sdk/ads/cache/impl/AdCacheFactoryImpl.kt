package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.ads.cache.denis.AdCacheDenisFactory
import org.bidon.sdk.ads.cache.impl.vladimir.AdCacheVladimirImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Factory implementation that creates version-specific AdCache instances.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        // Only Interstitial ads use version-based cache implementations
        // Banner and Rewarded always use default V1 implementation
        if (demandAd.adType != AdType.Interstitial) {
            return AdCacheImpl(
                demandAd = demandAd,
                scope = CoroutineScope(SdkDispatchers.Main),
                resolver = resolver
            )
        }

        val version = extractStrategyVersion(demandAd)
        return when (version) {
            AdCacheVersion.V1 -> AdCacheImpl(
                demandAd = demandAd,
                scope = CoroutineScope(SdkDispatchers.Main),
                resolver = resolver
            )

            AdCacheVersion.V2 -> AdCacheDenisFactory.create(
                demandAd = demandAd,
                resolver = resolver,
            )

            AdCacheVersion.V3 -> {
                AdCacheAndreiImpl(
                    demandAd = demandAd,
                    scope = CoroutineScope(SdkDispatchers.Main),
                    resolver = resolver
                )
            }

            AdCacheVersion.V4 -> {
                AdCacheVladimirImpl(
                    demandAd = demandAd,
                    resolver = resolver
                )
            }

            AdCacheVersion.V5 -> {
                AdCacheAlexImpl(
                    demandAd = demandAd,
                    resolver = resolver,
                )
            }
        }
    }

    private fun extractStrategyVersion(demandAd: DemandAd): AdCacheVersion {
        // Try to read from global BidonSdk extras
        val globalExtras = BidonSdk.getExtras()
        val cacheSettings = globalExtras["cache_settings"] as? Map<*, *>

        if (cacheSettings != null) {
            val adTypeKey = when (demandAd.adType) {
                AdType.Interstitial -> "interstitial"
                AdType.Banner -> "banner"
                AdType.Rewarded -> "rewarded_video"
            }

            val adTypeSettings = cacheSettings[adTypeKey] as? Map<*, *>
            val strategyVersion = adTypeSettings?.get("strategy_version") as? String

            if (strategyVersion != null) {
                return AdCacheVersion.fromString(strategyVersion)
            }
        }

        // Fallback to old approach for backward compatibility
        val demandExtras = demandAd.getExtras()
        val cacheSize = demandExtras["cache_size"] as? String
        return AdCacheVersion.fromString(cacheSize)
    }
}
