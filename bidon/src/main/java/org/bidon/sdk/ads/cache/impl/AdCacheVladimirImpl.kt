package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheVladimirImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
) : AdCache {
    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun peek(): AuctionResult? {
        TODO("Not yet implemented")
    }

    override fun pop(): AuctionResult? {
        TODO("Not yet implemented")
    }

    override suspend fun poll(): AuctionResult {
        TODO("Not yet implemented")
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override fun withSettings(settings: Cacheable.Settings) {
        TODO("Not yet implemented")
    }
}
