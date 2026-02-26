package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.execution.AdSourceResolver
import org.bidon.sdk.ads.cache.andr.execution.RtbResultsMerger
import org.bidon.sdk.ads.cache.andr.execution.WinLossNotifier
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionInfoFactory
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionRunnerFactory
import org.bidon.sdk.ads.cache.andr.store.AdStoreProvider
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
        val adType = demandAd.adType
        val tag = "AndrCache_${adType.code}"
        val adaptersSource = get<AdaptersSource>()
        val adStoreProvider = get<AdStoreProvider>()
        return AdCacheAndreiImpl(
            demandAd = demandAd,
            tag = tag,
            ioDispatcher = SdkDispatchers.IO,
            mainDispatcher = SdkDispatchers.Main,
            auctionResultStore = adStoreProvider.auctionResultStore(adType),
            auctionRunnerFactory =
                AuctionRunnerFactory(
                    tag = tag,
                    adSourceResolver = AdSourceResolver(tag = tag, adaptersSource = adaptersSource),
                    auctionConfigurator =
                        AuctionConfigurator(
                            tag = tag,
                            adaptersSource = adaptersSource,
                            getTokens = get<GetTokensUseCase>(),
                            getAuctionRequest = get<GetAuctionRequestUseCase>(),
                            biddingConfig = get<BiddingConfig>()
                        ),
                    auctionResolver = resolver,
                    demandStatistics = get<DemandStatistics>(),
                    infoFactory = AuctionInfoFactory(),
                    resultsCollector = get<ResultsCollector>(),
                    rtbResultsMerger = RtbResultsMerger(),
                    rtbResultsStore = adStoreProvider.rtbResultStore(adType),
                    winLossNotifier = WinLossNotifier(tag = tag),
                ),
        )
    }
}