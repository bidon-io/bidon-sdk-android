package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.ads.cache.andr.AdCacheAndrFactory
import org.bidon.sdk.ads.cache.denis.AdCacheDenisFactory
import org.bidon.sdk.ads.cache.impl.vladimir.AdCacheVladimirImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.utils.SdkDispatchers
import org.json.JSONObject

/**
 * Factory implementation that creates version-specific AdCache instances.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        // Only Interstitial and Banner ads use version-based cache implementations
        // Rewarded always use default V1 implementation
        if (demandAd.adType != AdType.Interstitial && demandAd.adType != AdType.Banner) {
            return AdCacheImpl(
                demandAd = demandAd,
                scope = CoroutineScope(SdkDispatchers.Main),
                resolver = resolver
            )
        }
        val version: AdCacheVersion = extractStrategyVersion(demandAd)
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
                AdCacheAndrFactory.create(
                    demandAd = demandAd,
                    resolver = resolver,
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
        return try {
            // Try to read from global BidonSdk extras
            val globalExtras = BidonSdk.getExtras()
            val cacheSettings = globalExtras["cache_settings"] as? JSONObject

            if (cacheSettings != null) {
                val adTypeKey = when (demandAd.adType) {
                    AdType.Interstitial -> "interstitial"
                    AdType.Banner -> "banner"
                    AdType.Rewarded -> "rewarded_video"
                }

                val adTypeSettings = cacheSettings.optJSONObject(adTypeKey)
                val strategyVersion = adTypeSettings?.optString("strategy_version")

                return if (!strategyVersion.isNullOrEmpty()) {
                    AdCacheVersion.fromString(strategyVersion)
                } else {
                    AdCacheVersion.Default
                }
            } else {
                return AdCacheVersion.Default
            }
        } catch (_: Exception) {
            AdCacheVersion.Default
        }
    }
}
