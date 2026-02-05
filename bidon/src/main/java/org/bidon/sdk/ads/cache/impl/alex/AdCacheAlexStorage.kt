package org.bidon.sdk.ads.cache.impl.alex

import kotlinx.coroutines.flow.MutableStateFlow
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.impl.AuctionKey
import org.bidon.sdk.auction.AuctionResolver

internal object AdCacheAlexStorage {
    private val cache = MutableStateFlow<Map<AuctionKey, AdCache>>(emptyMap())

    fun getCache(
        auctionKey: AuctionKey,
        demandAd: DemandAd,
        resolver: AuctionResolver
    ): AdCache {
        TODO("Not yet implemented.  Return existing AdCache or create a new AdCacherImpl if not present and save it under the auctionKey")
    }

    fun isDemandIdSingleton(demandId: DemandId): Boolean {
        return when (demandId.demandId) {
            "unityads" -> true
            else -> false
        }
    }
}