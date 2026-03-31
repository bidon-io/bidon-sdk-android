package org.bidon.sdk.ads.cache.twolevel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.twolevel.auction.TwoLevelAuctionController
import org.bidon.sdk.ads.cache.twolevel.pool.ManagerPool
import org.bidon.sdk.ads.cache.twolevel.storage.CacheStorage
import org.bidon.sdk.ads.cache.twolevel.storage.FallbackCacheStorage
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-Level Cache AdCache facade.
 *
 * Wraps the shared per-AdType [CacheStorage] (main) and [FallbackCacheStorage] (fallback)
 * singletons provided by [ManagerPool] via [TwoLevelCacheStores].
 *
 * Thread safety:
 *  - [cache] launches a coroutine on [scope]; [auctionRunning] guards duplicate starts.
 *  - [peek] uses non-suspend snapshot reads — safe from any thread.
 *  - [pop] uses runBlocking over the storage Mutex — lock contention is sub-millisecond.
 *  - [poll] suspends with a 100ms polling interval until an entry is available.
 */
internal class TwoLevelAdManager(
    override val demandAd: DemandAd,
    private val mainCache: CacheStorage,
    private val fallbackCache: FallbackCacheStorage,
    private val controller: TwoLevelAuctionController,
) : AdCache {

    private val adTypeLabel = demandAd.adType.code.uppercase()

    // Owned scope for launching cache() body; separate from the controller's scope.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // True while an auction is running — prevents duplicate starts and signals ManagerPool.
    private val auctionRunning = AtomicBoolean(false)

    /**
     * Returns true when no auction is currently running.
     * Queried by [ManagerPool] during periodic cleanup to decide whether the entry
     * is eligible for removal.
     */
    fun isIdle(): Boolean = !auctionRunning.get()

    // --- AdCache ---

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        scope.launch {
            cacheInternal(adTypeParam, onSuccess, onFailure)
        }
    }

    private suspend fun cacheInternal(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        // WARM START: if the cache already has an ad >= pricefloor, return it immediately.
        // Peek only — do NOT pop. The ad stays in cache until show() calls pop().
        val warmMain = mainCache.peek()
        if (warmMain != null && warmMain.adSource.getStats().price >= adTypeParam.pricefloor) {
            logInfo(TAG, "[$adTypeLabel] Warm start from Main: ${warmMain.adSource.getStats().demandId.demandId}")
            val info = buildSyntheticAuctionInfo(warmMain)
            withContext(Dispatchers.Main) { onSuccess(warmMain, info) }
            return
        }

        // COLD START — guard against duplicate loads.
        if (!auctionRunning.compareAndSet(false, true)) {
            logInfo(TAG, "[$adTypeLabel] Auction already running — ignoring duplicate load")
            return
        }

        // Tracks whether the first-fill onSuccess callback has fired.
        val firstFillFired = AtomicBoolean(false)

        try {
            // Reset iteration-threshold state before each auction round.
            mainCache.beginIteration()

            // controller.start() suspends until the pipeline completes all ad units.
            // The finally block correctly resets auctionRunning once the pipeline is done.
            controller.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                singleLoadCompletion = { winner ->
                    // Determine if this is the very first fill of this auction run.
                    val isFirst = firstFillFired.compareAndSet(false, true)

                    // Route: Main cache (sticky for first fill) → Fallback → destroy.
                    // Route: Main cache (sticky for first fill) → Fallback → destroy.
                    val mainResult = mainCache.insert(winner, sticky = isFirst)
                    logInfo(
                        TAG,
                        "[$adTypeLabel] singleLoadCompletion winner=${winner.adSource.getStats().demandId.demandId}" +
                            " isFirst=$isFirst mainResult=$mainResult",
                    )
                    if (!mainResult.isInserted) {
                        val fallbackResult = fallbackCache.insert(winner)
                        logInfo(TAG, "[$adTypeLabel] fallback insert: $fallbackResult")
                        if (!fallbackResult.isInserted) {
                            // Rejected by both caches — destroy to avoid ad source leak.
                            winner.adSource.destroy()
                            logInfo(TAG, "[$adTypeLabel] winner destroyed (rejected by both caches)")
                        }
                    }

                    // First fill → deliver onSuccess on main thread.
                    if (isFirst) {
                        val info = buildSyntheticAuctionInfo(winner)
                        logInfo(TAG, "[$adTypeLabel] firing onSuccess for first fill")
                        withContext(Dispatchers.Main) { onSuccess(winner, info) }
                        logInfo(TAG, "[$adTypeLabel] onSuccess delivered")
                    }
                },
                onComplete = { auctionInfo, error ->
                    if (error != null && !firstFillFired.get()) {
                        // Auction ended with no fills and the controller did not find a fallback ad.
                        withContext(Dispatchers.Main) { onFailure(auctionInfo, error) }
                    } else if (!firstFillFired.get()) {
                        // Fallback scenario: controller found a cached ad in fallback.
                        // Peek (don't pop) — ad stays in cache until show() calls pop().
                        val cached = mainCache.peek() ?: fallbackCache.peek()
                        if (cached != null) {
                            val info = auctionInfo ?: buildSyntheticAuctionInfo(cached)
                            withContext(Dispatchers.Main) { onSuccess(cached, info) }
                        } else {
                            // Edge case: fallback was emptied between controller check and here.
                            withContext(Dispatchers.Main) {
                                onFailure(auctionInfo, BidonError.NoFill(DemandId("fallback")))
                            }
                        }
                    }
                    // If firstFillFired == true, onSuccess was already delivered — nothing more to do.
                },
            )
        } finally {
            auctionRunning.set(false)
        }
    }

    /**
     * Uses non-suspend snapshot reads — safe to call from any thread synchronously.
     */
    override fun peek(): AuctionResult? =
        mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()

    /**
     * Pop from Main first, then Fallback.
     * runBlocking is acceptable here because AdCache.pop() is synchronous and the
     * storage Mutex is never held for long durations (sub-millisecond).
     */
    override fun pop(): AuctionResult? = runBlocking {
        mainCache.popFirst() ?: fallbackCache.popFirst()
    }

    /**
     * Suspends until an entry is available in either cache, then pops it.
     * Uses a 100ms polling interval — same pattern as other AdCache implementations.
     */
    override suspend fun poll(): AuctionResult {
        while (true) {
            val result = mainCache.popFirst() ?: fallbackCache.popFirst()
            if (result != null) return result
            delay(100)
        }
    }

    /**
     * Cancels any running auction but keeps the manager in [ManagerPool].
     * The pool's periodic cleanup handles removal of idle/stale entries.
     *
     * NOT removing from pool avoids unnecessary Pipeline/Controller recreation when
     * Appodeal calls destroyAd() after a NoFill and then immediately loadAd() again
     * (common pattern for Banner auto-refresh). The next getOrCreate() reuses
     * this manager via the live WeakReference instead of allocating a new one.
     */
    override fun clear() {
        logInfo(TAG, "[$adTypeLabel] clear()")
        controller.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: Two-Level Cache uses TwoLevelCacheConfig sourced from server extras, not Cacheable.Settings.
    }

    // ---

    /**
     * Builds a minimal [AuctionInfo] for warm-start and fallback-served ads where no real
     * auction info is available (e.g., serving a cached ad without a live auction response).
     */
    private fun buildSyntheticAuctionInfo(result: AuctionResult): AuctionInfo {
        val stats = result.adSource.getStats()
        val adUnit = stats.adUnit
        return AuctionInfo(
            auctionId = stats.auctionId ?: "-",
            auctionConfigurationId = null,
            auctionConfigurationUid = null,
            auctionTimeout = 0,
            auctionPricefloor = stats.auctionPricefloor,
            noBids = null,
            adUnits = listOf(
                AdUnitInfo(
                    demandId = stats.demandId.demandId,
                    label = adUnit?.label,
                    price = stats.price,
                    uid = adUnit?.uid,
                    bidType = adUnit?.bidType?.code,
                    fillStartTs = stats.fillStartTs,
                    fillFinishTs = stats.fillFinishTs,
                    status = "WIN",
                    ext = adUnit?.extra?.toString(),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
