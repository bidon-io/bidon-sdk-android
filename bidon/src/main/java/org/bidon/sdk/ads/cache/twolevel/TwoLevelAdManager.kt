package org.bidon.sdk.ads.cache.twolevel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.bidon.sdk.ads.cache.twolevel.storage.CacheStorage
import org.bidon.sdk.ads.cache.twolevel.storage.FallbackCacheStorage
import org.bidon.sdk.ads.cache.twolevel.storage.InsertResult
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-Level Cache AdCache facade.
 *
 * Wraps [CacheStorage] (main) and [FallbackCacheStorage] (fallback)
 * created per auctionKey by [org.bidon.sdk.ads.cache.twolevel.pool.ManagerPool].
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
     * Queried by ManagerPool during periodic cleanup.
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

        // COLD START — guard against duplicate loads (silent return per spec).
        if (!auctionRunning.compareAndSet(false, true)) {
            logInfo(TAG, "[$adTypeLabel] Auction already running — ignoring duplicate load")
            return
        }

        // Tracks whether the first-fill onSuccess callback has fired.
        val firstFillFired = AtomicBoolean(false)

        try {
            // Reset iteration-threshold state before each auction round.
            mainCache.beginIteration()

            controller.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                singleLoadCompletion = { winner ->
                    routeBidToCache(winner, firstFillFired, onSuccess)
                },
                shouldContinueAuction = {
                    // Stop when Main full AND Fallback full/disabled.
                    !(mainCache.isFullSnapshot && fallbackCache.isFullSnapshot)
                },
                onComplete = { auctionInfo, error ->
                    if (error != null && !firstFillFired.get()) {
                        withContext(Dispatchers.Main) { onFailure(auctionInfo, error) }
                    } else if (!firstFillFired.get()) {
                        // Fallback scenario: controller found a cached ad in fallback.
                        val cached = mainCache.peek() ?: fallbackCache.peek()
                        if (cached != null) {
                            val info = auctionInfo ?: buildSyntheticAuctionInfo(cached)
                            withContext(Dispatchers.Main) { onSuccess(cached, info) }
                        } else {
                            withContext(Dispatchers.Main) {
                                onFailure(auctionInfo, BidonError.NoFill(DemandId("fallback")))
                            }
                        }
                    }
                },
            )
        } finally {
            auctionRunning.set(false)
        }
    }

    /**
     * Routes a filled bid through the cache hierarchy:
     * Main (sticky for first) → evicted from Main → Fallback → destroy.
     */
    private suspend fun routeBidToCache(
        winner: AuctionResult,
        firstFillFired: AtomicBoolean,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        val isFirst = firstFillFired.compareAndSet(false, true)
        val demandId = winner.adSource.getStats().demandId.demandId

        // Try Main cache (sticky for first fill).
        val mainResult = mainCache.insert(winner, sticky = isFirst)
        logInfo(TAG, "[$adTypeLabel] route winner=$demandId isFirst=$isFirst mainResult=$mainResult")

        // Route evicted items from Main → Fallback.
        if (mainResult is InsertResult.Success) {
            mainResult.evicted.forEach { evictedAd ->
                val fbResult = fallbackCache.insert(evictedAd)
                if (!fbResult.isInserted) {
                    evictedAd.adSource.destroy()
                    logInfo(TAG, "[$adTypeLabel] evicted ad destroyed (Fallback rejected)")
                }
            }
        }

        // If Main rejected → try Fallback.
        if (!mainResult.isInserted) {
            val fallbackResult = fallbackCache.insert(winner)
            logInfo(TAG, "[$adTypeLabel] fallback insert: $fallbackResult")
            if (!fallbackResult.isInserted) {
                winner.adSource.destroy()
                logInfo(TAG, "[$adTypeLabel] winner destroyed (rejected by both caches)")
            }
        }

        // First fill → deliver onSuccess on main thread.
        if (isFirst) {
            val info = buildSyntheticAuctionInfo(winner)
            logInfo(TAG, "[$adTypeLabel] firing onSuccess for first fill")
            withContext(Dispatchers.Main) { onSuccess(winner, info) }
        }
    }

    /**
     * Uses non-suspend snapshot reads — safe to call from any thread synchronously.
     */
    override fun peek(): AuctionResult? =
        mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()

    /**
     * Pop from Main first, then Fallback. Cancels any running auction (per spec:
     * show() consumes the ad and stops background demand polling).
     */
    override fun pop(): AuctionResult? = runBlocking {
        val result = mainCache.popFirst() ?: fallbackCache.popFirst()
        if (result != null) {
            controller.cancel()
        }
        result
    }

    /**
     * Suspends until an entry is available in either cache, then pops it.
     */
    override suspend fun poll(): AuctionResult {
        while (true) {
            val result = mainCache.popFirst() ?: fallbackCache.popFirst()
            if (result != null) {
                controller.cancel()
                return result
            }
            delay(100)
        }
    }

    override fun clear() {
        logInfo(TAG, "[$adTypeLabel] clear()")
        controller.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: Two-Level Cache uses TwoLevelCacheConfig sourced from server extras.
    }

    // ---

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
