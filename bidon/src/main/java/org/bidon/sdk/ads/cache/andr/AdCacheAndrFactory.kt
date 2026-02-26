package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.execution.AdSourceResolver
import org.bidon.sdk.ads.cache.andr.execution.AdUnitPreparer
import org.bidon.sdk.ads.cache.andr.execution.RtbResultsMerger
import org.bidon.sdk.ads.cache.andr.execution.WinLossNotifier
import org.bidon.sdk.ads.cache.andr.execution.AuctionExecutorFactory
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersCollector
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersInfoCollector
import org.bidon.sdk.ads.cache.andr.preparation.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.preparation.AuctionInfoFactory
import org.bidon.sdk.ads.cache.andr.token.TokenCollector
import org.bidon.sdk.ads.cache.andr.token.TokensCollector
import org.bidon.sdk.ads.cache.andr.store.AdStoreProvider
import org.bidon.sdk.ads.cache.impl.AdCacheAndreiImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
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
        val auctionResultsStore = adStoreProvider.auctionResultStore(adType)
        val rtbResultsStore = adStoreProvider.rtbResultStore(adType)
        val demandStatistics = get<DemandStatistics>()
        val adaptersCollector =
            AdaptersCollector(
                adaptersSource = adaptersSource,
                rtbResultsStore = rtbResultsStore,
            )
        return AdCacheAndreiImpl(
            demandAd = demandAd,
            tag = tag,
            ioDispatcher = SdkDispatchers.IO,
            mainDispatcher = SdkDispatchers.Main,
            auctionResultsStore = auctionResultsStore,
            auctionRunnerFactory =
                AuctionRunnerFactory(
                    tag = tag,
                    auctionConfigurator =
                        AuctionConfigurator(
                            tag = tag,
                            adaptersCollector = adaptersCollector,
                            adaptersInfoCollector =
                                AdaptersInfoCollector(
                                    rtbResultsStore = rtbResultsStore,
                                ),
                            getAuctionRequestUseCase = get<GetAuctionRequestUseCase>(),
                            rtbResultsStore = rtbResultsStore,
                            tokensCollector =
                                TokensCollector(
                                    tag = tag,
                                    ioDispatcher = SdkDispatchers.IO,
                                    biddingConfig = get<BiddingConfig>(),
                                    tokenCollector = TokenCollector(),
                                ),
                        ),
                    auctionExecutorFactory =
                        AuctionExecutorFactory(
                            tag = tag,
                            adSourceResolver = AdSourceResolver(tag = tag),
                            adUnitPreparer =
                                AdUnitPreparer(
                                    rtbResultsStore = rtbResultsStore,
                                    rtbResultsMerger = RtbResultsMerger(),
                                    demandStatistics = demandStatistics,
                                ),
                            adaptersCollector = adaptersCollector,
                            demandStatistics = demandStatistics,
                            requestAdUnitUseCase = get<RequestAdUnitUseCase>(),
                            rtbResultsStore = rtbResultsStore,
                            winLossNotifier = WinLossNotifier(tag = tag),
                        ),
                    auctionResolver = resolver,
                    infoFactory = AuctionInfoFactory(),
                    resultsCollector = get<ResultsCollector>(),
                ),
        )
    }
}