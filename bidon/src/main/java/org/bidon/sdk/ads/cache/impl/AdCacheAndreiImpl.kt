package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.andr.AuctionRunnerFactory
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.ads.cache.andr.store.asString
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAndreiImpl(
    override val demandAd: DemandAd,
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val auctionResultsStore: AdStore<AuctionResultStore.Entry>,
    private val auctionRunnerFactory: AuctionRunnerFactory,
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
        val storedEntries =
            auctionResultsStore.peekAll().filter { it.price >= adTypeParam.pricefloor }

        logInfo(tag, "Cache started: ${storedEntries.asString()}")

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        val storedEntry = auctionResultsStore.peek()
        if (storedEntry != null) {
            processAuctionResult(storedEntry, null, onSuccess, onFailure)
        } else {
            auctionJob =
                scope.launch(ioDispatcher) {
                    auctionRunnerFactory
                        .create(this@AdCacheAndreiImpl)
                        .run(demandAd, adTypeParam)
                        .fold(
                            { (info, results) ->
                                auctionResultsStore
                                    .insert(results) { AuctionResultStore.Entry(it, info) }
                                val result = auctionResultsStore.poll()
                                processAuctionResult(result, null, onSuccess, onFailure)
                            },
                            {
                                processAuctionResult(null, it, onSuccess, onFailure)
                            },
                        )
                }
        }
    }

    override fun peek(): AuctionResult? = auctionResultsStore.peek()?.unwrap()

    override fun pop(): AuctionResult? = auctionResultsStore.pop()?.unwrap()

    override suspend fun poll(): AuctionResult = auctionResultsStore.poll().unwrap()

    override fun clear() {
        if (!isLoading.getAndSet(false)) {
            return
        }

        logInfo(tag, "Ad is loading, cancel auction")

        auctionJob?.cancel()
        auctionJob = null

        logInfo(tag, "Auction canceled")
    }

    private fun processAuctionResult(
        entry: AuctionResultStore.Entry?,
        cause: Throwable?,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        val auctionResult = entry?.auctionResult
        val auctionInfo = entry?.auctionInfo
        if (auctionResult != null && auctionInfo != null) {
            logInfo(tag, "Cache completed: ${auctionResultsStore.peekAll().asString()}")
            scope.launch(mainDispatcher) {
                onSuccess(auctionResult, auctionInfo)
            }
        } else {
            logInfo(tag, "Auction failed: ${auctionResultsStore.peekAll().asString()}")
            scope.launch(mainDispatcher) {
                onFailure(auctionInfo, cause ?: BidonError.Unspecified(null))
            }
        }
        isLoading.set(false)
    }

    override fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?
    ): Boolean = successCount + auctionResultsStore.size >= auctionResultsStore.capacity
}
