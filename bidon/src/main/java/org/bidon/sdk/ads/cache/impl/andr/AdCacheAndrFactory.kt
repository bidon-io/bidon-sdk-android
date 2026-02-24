package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.impl.AdCacheAndreiImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get

internal object AdCacheAndrFactory {
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
    ): AdCache {
        val tag = "AndrCache_${demandAd.adType.code}"
        // TODO : Add AdType
        return AdCacheAndreiImpl(
            demandAd = demandAd,
            tag = tag,
            ioDispatcher = SdkDispatchers.IO,
            mainDispatcher = SdkDispatchers.Main,
            auctionConfigurator =
                AuctionConfigurator(
                    tag = tag,
                    adaptersSource = get<AdaptersSource>(),
                    getTokens = get<GetTokensUseCase>(),
                    getAuctionRequest = get<GetAuctionRequestUseCase>(),
                    biddingConfig = get<BiddingConfig>()
                ),
            auctionResultStore = AuctionResultStore(),
            auctionStatistics =
                AuctionStatistics(
                    statsRequest = get<StatsRequestUseCase>(),
                    resolver = resolver,
                ),
            resultsCollector = get<ResultsCollector>(),
            rtbResultsStore = RtbResultStore(),
        )
    }
}