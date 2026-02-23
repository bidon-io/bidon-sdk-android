package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.impl.andr.AdStore
import org.bidon.sdk.ads.cache.impl.andr.AdUnitListMerger
import org.bidon.sdk.ads.cache.impl.andr.AdUnitStore
import org.bidon.sdk.ads.cache.impl.andr.AuctionConfigurator
import org.bidon.sdk.ads.cache.impl.andr.AuctionExecutor
import org.bidon.sdk.ads.cache.impl.andr.AuctionInfoFactory
import org.bidon.sdk.ads.cache.impl.andr.AuctionResultStore
import org.bidon.sdk.ads.cache.impl.andr.AuctionRunner
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAndreiImpl(
    override val demandAd: DemandAd,
    private val executionDispatcher: CoroutineDispatcher,
    private val callbackDispatcher: CoroutineDispatcher,
    private val resolver: AuctionResolver,
) : AdCache {
    private val tag = "${TAG}_${demandAd.adType.code}"

    private val scope: CoroutineScope by lazy {
        CoroutineScope(callbackDispatcher + SupervisorJob())
    }

    private val isLoading = AtomicBoolean(false)

    private var settings: Cacheable.Settings = Cacheable.DefaultSettings

    private var auctionJob: Job? = null
    private val auctionStat by lazy { get<AuctionStat>() }

    private val adUnitStore: AdStore<AdUnit, *> by lazy {
        AdUnitStore()
    }

    private val auctionResultStore: AdStore<AuctionResult, *> by lazy {
        AuctionResultStore()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
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

        val runner = createRunner()

        auctionJob =
            scope.launch {
                withContext(executionDispatcher) {
                    runner.run(demandAd, adTypeParam)
                }.fold(
                    { (info, results) -> processAuctionSuccess(results, info, onSuccess) },
                    { cause ->
                        val info = (cause as? BidonError.AuctionFailed)?.info
                        processAuctionFailure(info, cause, onFailure)
                    },
                )
            }
    }

    override fun peek(): AuctionResult? = auctionResultStore.peek()

    override fun pop(): AuctionResult? = auctionResultStore.pop()

    override suspend fun poll(): AuctionResult = auctionResultStore.poll()

    override fun clear() {
        adUnitStore.clear()
        auctionResultStore.clear()

        if (!isLoading.getAndSet(false)) {
            return
        }

        logInfo(tag, "Ad is loading, cancel auction")

        auctionStat.markAuctionCanceled()
        auctionJob?.cancel()
        auctionJob = null
        logInfo(tag, "Auction canceled")
    }

    private fun createRunner(): AuctionRunner {
        val tag = "AndrAuction"
        val executor =
            AuctionExecutor(
                tag,
                get(),
                get(),
                get(),
                adUnitStore,
                AdUnitListMerger(),
                object : AuctionStopCondition {
                    override fun shouldStop(
                        successCount: Int,
                        lastResult: AuctionResult,
                        next: AdUnit?,
                    ): Boolean = successCount >= auctionResultStore.capacity
                }
            )
        return AuctionRunner(
            tag,
            AuctionConfigurator(tag, get(), get(), get(), get()),
            executor,
            get(),
            auctionStat,
            AuctionInfoFactory(tag),
        )
    }

    private fun processAuctionSuccess(
        auctionResults: List<AuctionResult>,
        auctionInfo: AuctionInfo,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        scope.launch {
            auctionResultStore
                .insert(*auctionResults.toTypedArray())
                .also {
                    logInfo(
                        tag, "Auction completed: ${auctionResults.asString()}"
                    )
                }.also { isLoading.set(false) }
                .let { onSuccess.invoke(auctionResultStore.peek()!!, auctionInfo) }
        }
    }

    private fun processAuctionFailure(
        auctionInfo: AuctionInfo?,
        cause: Throwable,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        scope.launch {
            logInfo(tag, "Auction failed: ${auctionResultStore.peekAll().asString()}")
            isLoading.set(false)
            onFailure.invoke(auctionInfo, cause)
        }
    }

    private fun Collection<AuctionResult>.asString(): String =
        "(${this.size}) " +
            joinToString { auctionResult ->
                auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.price}" }
            }
}
