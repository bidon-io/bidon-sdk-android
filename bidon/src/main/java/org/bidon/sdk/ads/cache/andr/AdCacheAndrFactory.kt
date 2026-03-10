package org.bidon.sdk.ads.cache.andr

import kotlinx.coroutines.Dispatchers
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.andr.execution.AdSourceResolver
import org.bidon.sdk.ads.cache.andr.execution.AdUnitPreparer
import org.bidon.sdk.ads.cache.andr.execution.AuctionExecutorFactory
import org.bidon.sdk.ads.cache.andr.execution.RtbResultsMerger
import org.bidon.sdk.ads.cache.andr.execution.WinLossNotifier
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersCollector
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersInfoCollector
import org.bidon.sdk.ads.cache.andr.preparation.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.preparation.AuctionInfoFactory
import org.bidon.sdk.ads.cache.andr.store.AdStoreProvider
import org.bidon.sdk.ads.cache.andr.token.TokenCollector
import org.bidon.sdk.ads.cache.andr.token.TokensCollector
import org.bidon.sdk.ads.cache.impl.AdCacheAndreiImpl
import org.bidon.sdk.auction.AuctionResolver
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
        val ioDispatcher = Dispatchers.IO

        val adCacheStrategy = AdCacheStrategyFactory().create(demandAd)

        val adaptersSource = get<AdaptersSource>()
        val adStoreProvider = get<AdStoreProvider>()
        val auctionResultsStore =
            adStoreProvider.auctionResultStore(
                adCacheStrategy = adCacheStrategy,
                adType = adType
            )
        val rtbResultsStore =
            adStoreProvider.rtbResultStore(
                adCacheStrategy = adCacheStrategy,
                adType = adType
            )
        val adaptersCollector =
            AdaptersCollector(
                tag = tag,
                adaptersSource = adaptersSource,
                rtbResultsStore = rtbResultsStore,
            )
        val infoFactory = AuctionInfoFactory()

        return AdCacheAndreiImpl(
            demandAd = demandAd,
            tag = tag,
            ioDispatcher = ioDispatcher,
            mainDispatcher = SdkDispatchers.Main,
            adCacheStrategy = adCacheStrategy,
            auctionResultsStore = auctionResultsStore,
            auctionInfoFactory = infoFactory,
            auctionRunnerFactory =
                AuctionRunnerFactory(
                    tag = tag,
                    ioDispatcher = ioDispatcher,
                    auctionConfigurator =
                        AuctionConfigurator(
                            tag = tag,
                            adaptersCollector = adaptersCollector,
                            adaptersInfoCollector =
                                AdaptersInfoCollector(
                                    tag = tag,
                                    rtbResultsStore = rtbResultsStore,
                                ),
                            getAuctionRequestUseCase = get<GetAuctionRequestUseCase>(),
                            rtbResultsStore = rtbResultsStore,
                            tokensCollector =
                                TokensCollector(
                                    tag = tag,
                                    ioDispatcher = ioDispatcher,
                                    biddingConfig = get<BiddingConfig>(),
                                    tokenCollector = TokenCollector(tag = tag),
                                ),
                        ),
                    auctionExecutorFactory =
                        AuctionExecutorFactory(
                            tag = tag,
                            adCacheStrategy = adCacheStrategy,
                            adaptersCollector = adaptersCollector,
                            adSourceResolver = AdSourceResolver(tag = tag),
                            adUnitPreparer =
                                AdUnitPreparer(
                                    tag = tag,
                                    rtbResultsStore = rtbResultsStore,
                                    rtbResultsMerger = RtbResultsMerger(),
                                ),
                            requestAdUnitUseCase = get<RequestAdUnitUseCase>(),
                            rtbResultsStore = rtbResultsStore,
                            winLossNotifier = WinLossNotifier(tag = tag),
                        ),
                    auctionResolver = resolver,
                    infoFactory = infoFactory,
                ),
        )
    }
}