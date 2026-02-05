package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.ads.cache.denis.AdCacheDenisFactory
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Factory implementation that creates version-specific AdCache instances.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val biddingConfig: BiddingConfig,
    private val regulation: Regulation,
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
                adaptersSource = adaptersSource,
                getTokens = getTokens,
                getAuctionRequest = getAuctionRequest,
                biddingConfig = biddingConfig,
                regulation = regulation,
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
