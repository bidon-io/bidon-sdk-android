package org.bidon.sdk.ads.cache.andr

import kotlinx.coroutines.CoroutineDispatcher
import org.bidon.sdk.ads.cache.andr.analytics.AuctionStatistics
import org.bidon.sdk.ads.cache.andr.execution.AuctionExecutorFactory
import org.bidon.sdk.ads.cache.andr.preparation.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.preparation.AuctionInfoFactory
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.get

internal class AuctionRunnerFactory(
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val auctionConfigurator: AuctionConfigurator,
    private val auctionExecutorFactory: AuctionExecutorFactory,
    private val auctionResolver: AuctionResolver,
    private val infoFactory: AuctionInfoFactory,
) {
    fun create(stopCondition: AuctionStopCondition): AuctionRunner =
        AuctionRunner(
            tag,
            auctionConfigurator = auctionConfigurator,
            infoFactory = infoFactory,
            statistics =
                AuctionStatistics(
                    tag = tag,
                    ioDispatcher = ioDispatcher,
                    statsRequest = get<StatsRequestUseCase>(),
                    resolver = auctionResolver
                ),
            executor = auctionExecutorFactory.create(stopCondition),
            resultsCollector = get<ResultsCollector>(),
        )
}
