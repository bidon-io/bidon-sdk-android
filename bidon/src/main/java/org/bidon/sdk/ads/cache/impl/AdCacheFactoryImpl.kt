package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Factory implementation that creates version-specific AdCache instances.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        val version = AdCacheVersion.fromInt(demandAd.getExtras()["strategy_version"] as? Int)
        return when (version) {
            AdCacheVersion.V1 -> AdCacheImpl(
                demandAd = demandAd,
                scope = CoroutineScope(SdkDispatchers.Main),
                resolver = resolver
            )

            AdCacheVersion.V2 -> {
                AdCacheDenisImpl(
                    demandAd = demandAd,
                    resolver = resolver,
                )
            }

            AdCacheVersion.V3 -> {
                AdCacheAndreiImpl(
                    demandAd = demandAd,
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
}
