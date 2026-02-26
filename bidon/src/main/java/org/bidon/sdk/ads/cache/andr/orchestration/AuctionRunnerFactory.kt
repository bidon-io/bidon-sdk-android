package org.bidon.sdk.ads.cache.andr.orchestration

import org.bidon.sdk.ads.cache.andr.analytics.AuctionStatistics
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.execution.AdSourceResolver
import org.bidon.sdk.ads.cache.andr.execution.AdUnitPreparer
import org.bidon.sdk.ads.cache.andr.execution.DefaultAuctionExecutor
import org.bidon.sdk.ads.cache.andr.execution.RtbResultsMerger
import org.bidon.sdk.ads.cache.andr.execution.WinLossNotifier
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.get

internal class AuctionRunnerFactory(
    private val tag: String,
    private val auctionConfigurator: AuctionConfigurator,
    private val auctionResolver: AuctionResolver,
    private val demandStatistics: DemandStatistics,
    private val resultsCollector: ResultsCollector,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
    private val adSourceResolver: AdSourceResolver,
    private val infoFactory: AuctionInfoFactory,
    private val rtbResultsMerger: RtbResultsMerger,
    private val winLossNotifier: WinLossNotifier,
) {
    fun create(stopCondition: AuctionStopCondition): AuctionRunner =
        AuctionRunner(
            tag,
            infoFactory = infoFactory,
            statistics =
                AuctionStatistics(
                    statsRequest = get<StatsRequestUseCase>(),
                    resolver = auctionResolver
                ),
            configurator = auctionConfigurator,
            executor =
                DefaultAuctionExecutor(
                    tag = tag,
                    adUnitPreparer =
                        AdUnitPreparer(
                            rtbResultStore = rtbResultsStore,
                            rtbResultsMerger = rtbResultsMerger,
                            demandStatistics = demandStatistics
                        ),
                    adSourceResolver = adSourceResolver,
                    winLossNotifier = winLossNotifier,
                    requestAdUnit = get<RequestAdUnitUseCase>(),
                    rtbResultStore = rtbResultsStore,
                    statsRepository = demandStatistics,
                    stopCondition = stopCondition,
                ),
            resultsCollector = resultsCollector,
        )
}
