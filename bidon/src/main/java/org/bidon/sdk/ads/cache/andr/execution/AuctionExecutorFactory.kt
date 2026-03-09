package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.ads.cache.andr.AdCacheStrategy
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersCollector
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase

internal class AuctionExecutorFactory(
    private val tag: String,
    private val adCacheStrategy: AdCacheStrategy,
    private val adSourceResolver: AdSourceResolver,
    private val adUnitPreparer: AdUnitPreparer,
    private val adaptersCollector: AdaptersCollector,
    private val requestAdUnitUseCase: RequestAdUnitUseCase,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
    private val winLossNotifier: WinLossNotifier,
) {
    fun create(stopCondition: AuctionStopCondition): AuctionExecutor =
        DefaultAuctionExecutor(
            tag = tag,
            adaptersCollector = adaptersCollector,
            adSourceResolver = adSourceResolver,
            adUnitPreparer = adUnitPreparer,
            batchSize = adCacheStrategy.batchSize,
            winLossNotifier = winLossNotifier,
            requestAdUnitUseCase = requestAdUnitUseCase,
            rtbResultStore = rtbResultsStore,
            stopCondition = stopCondition,
        )
}
