package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.ads.cache.denis.AdCacheDenisFactory
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Factory implementation that creates version-specific AdCache instances.
 *
 * Note: V2 (AdCacheDenisFactory) obtains its specific dependencies directly
 * from DI container to keep them encapsulated within V2 implementation.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        val version = AdCacheVersion.fromInt(demandAd.getExtras()["cache_size"] as? Int)
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
