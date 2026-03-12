package org.bidon.sdk.ads.cache.twolevel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.DemandAd
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
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-Level Cache AdCache facade. Mirrors iOS ZhenyaFullscreenAdManager.
 *
 * Wraps the shared per-AdType [CacheStorage] (main) and [FallbackCacheStorage] (fallback)
 * singletons provided by [ManagerPool] via [TwoLevelCacheStores]. The [auctionKey] is
 * stored at construction time and used to identify this manager in the pool.
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
    private val auctionKey: String,
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
        // WARM START — mirrors iOS ZhenyaFullscreenAdManager.loadAd warm-start check.
        // iOS only checks Main cache here; Fallback is only consulted after auction failure.
        val warmMain = mainCache.peek()
        if (warmMain != null && warmMain.adSource.getStats().price >= adTypeParam.pricefloor) {
            val popped = mainCache.popFirst()
            if (popped != null) {
                logInfo(TAG, "[$adTypeLabel] Warm start from Main: ${popped.adSource.getStats().demandId.demandId}")
                val info = buildSyntheticAuctionInfo(popped)
                withContext(Dispatchers.Main) { onSuccess(popped, info) }
                return
            }
        }

        // COLD START — guard against duplicate loads.
        if (!auctionRunning.compareAndSet(false, true)) {
            logInfo(TAG, "[$adTypeLabel] Auction already running — ignoring duplicate load")
            return
        }

        // Tracks whether the first-fill onSuccess callback has fired.
        val firstFillFired = AtomicBoolean(false)

        try {
            // iOS: beginIteration() resets iterationMaxPrice before each auction round.
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
                    // Mirrors iOS: Cacher.Main.interstitialStorage.insert(ad, sticky: isFirstLoad)
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

                    // iOS: first fill → delegate?.adManager(self, didLoad: ad, …) on main thread.
                    if (isFirst) {
                        val info = buildSyntheticAuctionInfo(winner)
                        withContext(Dispatchers.Main) { onSuccess(winner, info) }
                    }
                },
                onComplete = { auctionInfo, error ->
                    if (error != null && !firstFillFired.get()) {
                        // Auction ended with no fills and the controller did not find a fallback ad.
                        withContext(Dispatchers.Main) { onFailure(auctionInfo, error) }
                    }
                    // If firstFillFired == true, onSuccess was already delivered — nothing more to do.
                },
            )
        } finally {
            auctionRunning.set(false)
        }
    }

    /**
     * iOS isReady = Main.peek() != nil || Fallback.peek() != nil.
     * Uses non-suspend snapshot reads — safe to call from any thread synchronously.
     */
    override fun peek(): AuctionResult? =
        mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()

    /**
     * iOS show flow: pop from Main first, then Fallback.
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
     * Detaches this manager from [ManagerPool]. Does NOT clear the shared per-AdType stores.
     * iOS equivalent: ZhenyaManagerPool.removeManager().
     */
    override fun clear() {
        logInfo(TAG, "[$adTypeLabel] clear() — detaching from pool auctionKey=$auctionKey")
        scope.launch {
            ManagerPool.remove(auctionKey)
        }
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
                    label = null,
                    price = stats.price,
                    uid = null,
                    bidType = null,
                    fillStartTs = null,
                    fillFinishTs = null,
                    status = "WIN",
                    ext = null,
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
