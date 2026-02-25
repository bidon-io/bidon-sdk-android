package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.ads.cache.impl.AdCacheAndreiImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get

internal object AdCacheAndrFactory {
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
    ): AdCache {
        val tag = "AndrCache_${demandAd.adType.code}"
        val adaptersSource = get<AdaptersSource>()
        return AdCacheAndreiImpl(
            demandAd = demandAd,
            tag = tag,
            ioDispatcher = SdkDispatchers.IO,
            mainDispatcher = SdkDispatchers.Main,
            adaptersSource = adaptersSource,
            auctionConfigurator =
                AuctionConfigurator(
                    tag = tag,
                    adaptersSource = adaptersSource,
                    getTokens = get<GetTokensUseCase>(),
                    getAuctionRequest = get<GetAuctionRequestUseCase>(),
                    biddingConfig = get<BiddingConfig>()
                ),
            auctionResolver = resolver,
            auctionResultStore = AuctionResultStore(),
            resultsCollector = get<ResultsCollector>(),
            rtbResultsStore = RtbResultStore(),
        )
    }
}