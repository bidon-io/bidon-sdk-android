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
    private val refillThreshold: Int,
    private val auctionRunnerFactory: AuctionRunnerFactory,
) : AdCache,
    AuctionStopCondition {
    private val scope: CoroutineScope by lazy {
        CoroutineScope(mainDispatcher + SupervisorJob())
    }

    private val isLoading = AtomicBoolean(false)

    private var auctionJob: Job? = null
    private var refillJob: Job? = null
    private var lastAdTypeParam: AdTypeParam? = null

    override fun withSettings(settings: Cacheable.Settings) {
        // Ignore
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        lastAdTypeParam = adTypeParam

        val storedEntries =
            auctionResultsStore.peekAll().filter { it.price >= adTypeParam.pricefloor }

        logInfo(tag, "Cache: ${storedEntries.size}/${auctionResultsStore.peekAll().size} above pricefloor(${adTypeParam.pricefloor}), entries=${storedEntries.asString()}")

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        val storedEntry = auctionResultsStore.peek()
        if (storedEntry != null) {
            logInfo(tag, "Reusing stored entry: ${storedEntry.demandId}:${storedEntry.price}")
            processAuctionResult(storedEntry, null, onSuccess, onFailure)
        } else {
            logInfo(tag, "No stored entry, starting auction")
            auctionJob =
                scope.launch(ioDispatcher) {
                    refillJob?.join()

                    val refilled = auctionResultsStore.peek()
                    if (refilled != null) {
                        logInfo(tag, "Refill completed before demand auction, reusing: ${refilled.demandId}:${refilled.price}")
                        processAuctionResult(refilled, null, onSuccess, onFailure)
                        return@launch
                    }

                    runAuctionAndFillStore(adTypeParam)
                        .fold(
                            {
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

    override fun pop(): AuctionResult? {
        val entry = auctionResultsStore.pop()
        if (entry != null) {
            maybeStartBackgroundRefill()
        }
        return entry?.unwrap()
    }

    override suspend fun poll(): AuctionResult = auctionResultsStore.poll().unwrap()

    override fun clear() {
        refillJob?.cancel()
        refillJob = null
        lastAdTypeParam = null

        if (!isLoading.getAndSet(false)) {
            return
        }

        auctionJob?.cancel()
        auctionJob = null

        logInfo(tag, "Auction canceled")
    }

    private suspend fun runAuctionAndFillStore(adTypeParam: AdTypeParam): Result<Pair<AuctionInfo, List<AuctionResult>>> {
        return auctionRunnerFactory
            .create(this@AdCacheAndreiImpl)
            .run(demandAd, adTypeParam)
            .onSuccess { (info, results) ->
                auctionResultsStore.insert(results) { AuctionResultStore.Entry(it, info) }
                logInfo(tag, "Auction completed: +${results.size}, store=${auctionResultsStore.peekAll().asString()}")
            }
    }

    private fun maybeStartBackgroundRefill() {
        val adTypeParam = lastAdTypeParam ?: return
        if (auctionResultsStore.peekAll().size > refillThreshold) return
        if (refillJob?.isActive == true) return

        logInfo(tag, "Background refill triggered (threshold=$refillThreshold)")
        refillJob = scope.launch(ioDispatcher) {
            runAuctionAndFillStore(adTypeParam)
                .onFailure { logInfo(tag, "Background refill failed: ${it.message}") }
        }
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
            logInfo(tag, "Auction failed: ${cause?.message}, store=${auctionResultsStore.peekAll().asString()}")
            scope.launch(mainDispatcher) {
                onFailure(auctionInfo, cause ?: BidonError.Unspecified(null))
            }
        }
        isLoading.set(false)
    }

    override fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?,
    ): Boolean {
        val shouldStop = successCount >= auctionResultsStore.capacity
        logInfo(tag, "shouldStop: successCount=$successCount, capacity=${auctionResultsStore.capacity} -> $shouldStop")
        return shouldStop
    }
}
