package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.impl.alex.AdCacheAlexStorage
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult

internal typealias AuctionKey = String

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAlexImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
) : AdCache {

    private var adCache: AdCache? = null

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        val auctionKeyForTest: AuctionKey = "1O16JOGPG0400" // don't use adTypeParam.auctionKey
        adCache = AdCacheAlexStorage.getCache(
            auctionKey = auctionKeyForTest,
            demandAd = demandAd,
            resolver = resolver
        )
        adCache?.cache(
            adTypeParam,
            onSuccess,
            onFailure
        )
    }

    override fun peek(): AuctionResult? {
        return adCache?.peek()
    }

    override fun pop(): AuctionResult? {
        return adCache?.pop()
    }

    override suspend fun poll(): AuctionResult {
        return adCache?.poll() ?: throw IllegalStateException("AdCache is not initialized")
    }

    override fun clear() {
        adCache?.clear()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        adCache?.withSettings(settings)
    }

}
