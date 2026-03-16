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
import org.bidon.sdk.ads.cache.andr.AdCacheStrategy
import org.bidon.sdk.ads.cache.andr.AuctionRunnerFactory
import org.bidon.sdk.ads.cache.andr.RefillCoordinator
import org.bidon.sdk.ads.cache.andr.preparation.AuctionInfoFactory
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.ads.cache.andr.store.asString
import org.bidon.sdk.ads.cache.andr.store.filterPrice
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private val auctionInfoFactory: AuctionInfoFactory,
    private val refillCoordinator: RefillCoordinator,
) : AdCache,
    AuctionStopCondition {
    private val scope: CoroutineScope by lazy {
        CoroutineScope(mainDispatcher + SupervisorJob())
    }

    private val isLoading = AtomicBoolean(false)

    private val auctionJobRef = AtomicReference<Job?>(null)

    private val lastAuctionIdRef = AtomicReference<String?>(null)

    override fun withSettings(settings: Cacheable.Settings) {
        // ignore
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        refillCoordinator.acquire(this, adTypeParam)

        val storedEntries = peekAllEntries().filterPrice(adTypeParam.pricefloor)

        logInfo(
            tag,
            "Cache: ${storedEntries.size}/${size()} above pricefloor(${adTypeParam.pricefloor}), entries=${storedEntries.asString()}"
        )

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        val storedEntry = peekEntry()
        if (storedEntry != null) {
            logInfo(tag, "Reusing stored entry: ${storedEntry.demandId}:${storedEntry.price}")
            processAuctionResult(storedEntry, null, onSuccess, onFailure)
            return
        }

        logInfo(tag, "No stored entry, starting auction")
        auctionJobRef.set(
            scope.launch(ioDispatcher) {
                refillCoordinator.join()

                val refilled = peekEntry()
                if (refilled != null) {
                    logInfo(
                        tag,
                        "Refill completed before demand auction, reusing: ${refilled.demandId}:${refilled.price}"
                    )
                    processAuctionResult(refilled, null, onSuccess, onFailure)
                    return@launch
                }

                runAuctionAndFillStore(adTypeParam)
                    .fold(
                        {
                            val result = peekEntry()
                            processAuctionResult(result, null, onSuccess, onFailure)
                        },
                        {
                            processAuctionResult(null, it, onSuccess, onFailure)
                        },
                    )
            }
        )
    }

    override fun peek(): AuctionResult? = peekEntry()?.unwrap()

    override fun pop(): AuctionResult? =
        auctionResultsStore
            .pop()
            ?.unwrap()
            ?.also {
                logInfo(tag, "Pop: store=${peekAllEntries().size}")
                maybeStartBackgroundRefill()
            }

    override suspend fun poll(): AuctionResult = auctionResultsStore.poll().unwrap()

    override fun clear() {
        refillCoordinator.release(this)

        lastAuctionIdRef.set(null)

        if (!isLoading.getAndSet(false)) {
            return
        }

        auctionJobRef.getAndSet(null)?.cancel()

        logInfo(tag, "Auction canceled")
    }

    private fun capacity(): Int = auctionResultsStore.capacity

    private fun size(): Int = peekAllEntries().size

    private fun peekEntry(): AuctionResultStore.Entry? = auctionResultsStore.peek()

    private fun peekAllEntries(): Set<AuctionResultStore.Entry> = auctionResultsStore.peekAll()

    private suspend fun runAuctionAndFillStore(adTypeParam: AdTypeParam): Result<Pair<AuctionInfo, List<AuctionResult>>> =
        auctionRunnerFactory
            .create(this@AdCacheAndreiImpl)
            .run(demandAd, adTypeParam)
            .onSuccess { (info, results) ->
                // Save last info
                lastAuctionIdRef.set(info.auctionId)
                // Fill store
                auctionResultsStore.insert(results) { AuctionResultStore.Entry(it, info) }
                logInfo(
                    tag,
                    "Auction completed: +${results.size}, store=${peekAllEntries().asString()}"
                )
            }

    private fun maybeStartBackgroundRefill() {
        refillCoordinator.maybeStart(isLoading = isLoading.get()) { adTypeParam ->
            runAuctionAndFillStore(adTypeParam)
                .onSuccess { (info, results) ->
                    refillCoordinator.recordResult(
                        results.isNotEmpty(),
                        info.auctionTimeout
                    )
                }.onFailure {
                    refillCoordinator.recordFailure()
                    logInfo(tag, "Background refill failed: ${it.message}")
                }
        }
    }

    private fun processAuctionResult(
        entry: AuctionResultStore.Entry?,
        cause: Throwable?,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        val cachedLog = peekAllEntries().asString()
        val auctionResult = entry?.auctionResult
        val lastId = lastAuctionIdRef.get()
        val auctionInfo =
            entry?.auctionInfo?.takeIf { lastId == null || it.auctionId == lastId }
                ?: auctionResult?.let(auctionInfoFactory::create)
        if (auctionResult != null && auctionInfo != null) {
            logInfo(tag, "Cache completed: $cachedLog")
            scope.launch(mainDispatcher) {
                onSuccess(auctionResult, auctionInfo)
            }
        } else {
            logInfo(tag, "Auction failed: ${cause?.message}, store=$cachedLog")
            scope.launch(mainDispatcher) {
                onFailure(null, cause ?: BidonError.Unspecified(null))
            }
        }
        isLoading.set(false)
        logInfo(tag, "Loading finished, store=${peekAllEntries().size}")
    }

    override fun shouldStop(
        successCount: Int,
        lastResult: AuctionResult,
        next: AdUnit?,
    ): Boolean {
        val threshold = capacity() - size()
        return (successCount >= threshold).also {
            logInfo(
                tag,
                "shouldStop: successCount=$successCount, remaining=$threshold (capacity=${capacity()}, cached=${size()}) -> $it"
            )
        }
    }
}
