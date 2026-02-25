package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.andr.execution.DefaultAuctionExecutor
import org.bidon.sdk.ads.cache.andr.execution.RtbResultsMerger
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionConfigurator
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionInfoFactory
import org.bidon.sdk.ads.cache.andr.orchestration.AuctionRunner
import org.bidon.sdk.ads.cache.andr.analytics.AuctionStatistics
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.ads.cache.andr.store.asString
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.get
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAndreiImpl(
    override val demandAd: DemandAd,
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val adaptersSource: AdaptersSource,
    private val auctionConfigurator: AuctionConfigurator,
    private val auctionResolver: AuctionResolver,
    private val auctionResultStore: AdStore<AuctionResultStore.Entry>,
    private val resultsCollector: ResultsCollector,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
) : AdCache,
    AuctionStopCondition {
    private val scope: CoroutineScope by lazy {
        CoroutineScope(mainDispatcher + SupervisorJob())
    }

    private val isLoading = AtomicBoolean(false)

    private var auctionJob: Job? = null

    override fun withSettings(settings: Cacheable.Settings) {
        // Ignore
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(tag, "Cache started: ${auctionResultStore.peekAll().asString()}")

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        logInfo(tag, "Cache ad: $adTypeParam")

        auctionJob =
            scope.launch {
                val runResult =
                    withContext(ioDispatcher) { createRunner().run(demandAd, adTypeParam) }
                runResult.fold(
                    { (info, results) ->
                        auctionResultStore
                            .insert(results) { AuctionResultStore.Entry(it, info) }
                        processAuctionResult(info, null, onSuccess, onFailure)
                    },
                    {
                        val info = (it as? BidonError.AuctionFailed)?.info
                        processAuctionResult(info, it, onSuccess, onFailure)
                    },
                )
            }
    }

    override fun peek(): AuctionResult? = auctionResultStore.peek()?.unwrap()

    override fun pop(): AuctionResult? = auctionResultStore.pop()?.unwrap()

    override suspend fun poll(): AuctionResult = auctionResultStore.poll().unwrap()

    override fun clear() {
        rtbResultsStore.clear()
        auctionResultStore.clear()

        if (!isLoading.getAndSet(false)) {
            return
        }

        logInfo(tag, "Ad is loading, cancel auction")

        auctionJob?.cancel()
        auctionJob = null

        logInfo(tag, "Auction canceled")
    }

    private fun createRunner(): AuctionRunner =
        AuctionRunner(
            tag,
            AuctionInfoFactory(),
            AuctionStatistics(get<StatsRequestUseCase>(), auctionResolver),
            auctionConfigurator,
            DefaultAuctionExecutor(
                tag = tag,
                adaptersSource = adaptersSource,
                requestAdUnit = get<RequestAdUnitUseCase>(),
                rtbResultStore = rtbResultsStore,
                rtbResultsMerger = RtbResultsMerger(),
                statsRepository = get<DemandStatistics>(),
                stopCondition = this,
            ),
            resultsCollector,
        )

    private fun processAuctionResult(
        auctionInfo: AuctionInfo?,
        cause: Throwable?,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        val stored = auctionResultStore.peekAll()
        val first = stored.firstOrNull()
        if (first != null) {
            logInfo(tag, "Auction completed: ${stored.asString()}")
            onSuccess(first.auctionResult, first.auctionInfo)
        } else {
            logInfo(tag, "Auction failed: ${stored.asString()}")
            onFailure.invoke(auctionInfo, cause ?: BidonError.AuctionFailed(auctionInfo, null))
        }
        isLoading.set(false)
    }

    override fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?
    ): Boolean = successCount + auctionResultStore.size >= auctionResultStore.capacity
}
