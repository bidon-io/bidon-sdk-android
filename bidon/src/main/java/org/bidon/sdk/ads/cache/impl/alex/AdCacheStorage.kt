package org.bidon.sdk.ads.cache.impl.alex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.auction.AuctionResolver

internal typealias AuctionKey = String

/**
 * Global cache storage that manages AdCache instances by auctionKey.
 * Allows sharing caches across multiple ad requests with the same auctionKey.
 */
internal object AdCacheStorage {
    private val cache = MutableStateFlow<Map<AuctionKey, AdCacher>>(emptyMap())

    fun getCache(
        auctionKey: AuctionKey,
        demandAd: DemandAd,
        resolver: AuctionResolver
    ): AdCache {
        // Check if cache already exists for this auctionKey
        cache.value[auctionKey]?.let { return it }

        // Create new cache and store it
        val newCache = AdCacher(
            demandAd = demandAd,
            resolver = resolver,
        )

        cache.update { currentMap ->
            currentMap + (auctionKey to newCache)
        }

        return newCache
    }

    fun isDemandIdSingleton(demandId: DemandId): Boolean {
        return demandId.demandId != "bidmachine"
    }
}
