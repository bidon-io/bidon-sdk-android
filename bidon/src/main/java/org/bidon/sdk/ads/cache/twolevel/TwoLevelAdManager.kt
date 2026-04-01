package org.bidon.sdk.ads.cache.twolevel

import android.os.SystemClock
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
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-Level Cache AdCache facade per AD_CACHE_SPEC.md.
 */
internal class TwoLevelAdManager(
    override val demandAd: DemandAd,
    private val mainCache: CacheStorage,
    private val fallbackCache: FallbackCacheStorage,
    private val controller: TwoLevelAuctionController,
) : AdCache {

    private val adTypeLabel = demandAd.adType.code.uppercase()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val auctionRunning = AtomicBoolean(false)

    @Volatile
    private var auctionJob: Job? = null

    /** Timestamp of last auction completion. Used by [pool.ManagerPool] for idle-time cleanup. */
    @Volatile
    var lastActiveAt: Long = SystemClock.elapsedRealtime()

    fun isIdle(): Boolean = !auctionRunning.get()

    // --- AdCache ---

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        auctionJob = scope.launch {
            cacheInternal(adTypeParam, onSuccess, onFailure)
        }
    }

    private suspend fun cacheInternal(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        // Step 1: Main.peek() — instant cache hit
        val warmMain = mainCache.peek()
        if (warmMain != null) {
            logInfo(TAG, "[$adTypeLabel] Cache hit from Main: ${warmMain.adSource.getStats().demandId.demandId}")
            val info = buildSyntheticAuctionInfo(warmMain)
            withContext(Dispatchers.Main) { onSuccess(warmMain, info) }
            return
        }

        // Step 2: auction running → silent return
        if (!auctionRunning.compareAndSet(false, true)) {
            logInfo(TAG, "[$adTypeLabel] Auction already running — silent return")
            return
        }

        val firstFillFired = AtomicBoolean(false)

        try {
            controller.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                singleLoadCompletion = { winner ->
                    routeBidToCache(winner, firstFillFired, onSuccess)
                },
                shouldContinueAuction = { ecpm ->
                    // Pre-filter per spec §7/§11: can any cache accept this bid?
                    val bar = mainCache.thresholdBar
                    val canAcceptMain = !mainCache.isFull && (bar == null || ecpm >= bar)
                    val canAcceptFallback = !fallbackCache.isDisabled &&
                        (!fallbackCache.isFull || ecpm > (fallbackCache.cheapestPrice ?: 0.0))
                    canAcceptMain || canAcceptFallback
                },
                onComplete = { auctionInfo, error ->
                    if (!firstFillFired.get()) {
                        // No bid was delivered to user — try Fallback rescue
                        val fallbackAd = fallbackCache.peek()
                        if (fallbackAd != null) {
                            val info = auctionInfo ?: buildSyntheticAuctionInfo(fallbackAd)
                            withContext(Dispatchers.Main) { onSuccess(fallbackAd, info) }
                        } else {
                            val failError = error ?: BidonError.NoFill(DemandId("auction"))
                            withContext(Dispatchers.Main) { onFailure(auctionInfo, failError) }
                        }
                    }
                    // firstFillFired == true → onSuccess already delivered, nothing to do.
                },
            )
        } finally {
            auctionRunning.set(false)
            lastActiveAt = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Routes a filled bid per spec pseudocode:
     * Main (sticky for first) → Fallback (with eviction) → destroy.
     * Sets RoundStatus: WIN (first), CACHE (cached), LOSE (destroyed).
     */
    private suspend fun routeBidToCache(
        winner: AuctionResult,
        firstFillFired: AtomicBoolean,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        val isFirst = firstFillFired.compareAndSet(false, true)
        val demandId = winner.adSource.getStats().demandId.demandId
        val price = winner.adSource.getStats().price

        // Attempt Main
        if (!mainCache.isFull) {
            val mainResult = mainCache.insert(winner, sticky = isFirst)
            if (mainResult.isInserted) {
                if (isFirst) {
                    // WIN — first fill, deliver to user. Stats keep Successful → converted to Win.
                    logInfo(TAG, "[$adTypeLabel] WIN: $demandId price=$price (sticky)")
                    val info = buildSyntheticAuctionInfo(winner)
                    withContext(Dispatchers.Main) { onSuccess(winner, info) }
                } else {
                    // CACHE — silently cached in Main.
                    winner.adSource.markFillFinished(RoundStatus.Cached, price)
                    logInfo(TAG, "[$adTypeLabel] CACHE (Main): $demandId price=$price")
                }
                return
            }
            // Main rejected (threshold) — fall through to Fallback.
            logInfo(TAG, "[$adTypeLabel] Main rejected: $demandId mainResult=$mainResult")
        }

        // Main didn't accept → Fallback
        if (fallbackCache.isDisabled) {
            winner.adSource.markFillFinished(RoundStatus.Lose, price)
            winner.adSource.destroy()
            logInfo(TAG, "[$adTypeLabel] LOSE (Fb disabled): $demandId price=$price")
            if (isFirst) {
                // Edge case: first bid rejected by Main AND Fallback disabled.
                // No didLoad fired — will be handled by onComplete no-fill path.
                firstFillFired.set(false)
            }
            return
        }

        val fbResult = fallbackCache.insert(winner)
        if (fbResult.isInserted) {
            winner.adSource.markFillFinished(RoundStatus.Cached, price)
            logInfo(TAG, "[$adTypeLabel] CACHE (Fallback): $demandId price=$price")
        } else {
            winner.adSource.markFillFinished(RoundStatus.Lose, price)
            winner.adSource.destroy()
            logInfo(TAG, "[$adTypeLabel] LOSE (both rejected): $demandId price=$price")
        }

        // First fill that went to Fallback (not Main) — still deliver didLoad.
        if (isFirst && fbResult.isInserted) {
            logInfo(TAG, "[$adTypeLabel] WIN (from Fallback): $demandId price=$price")
            val info = buildSyntheticAuctionInfo(winner)
            withContext(Dispatchers.Main) { onSuccess(winner, info) }
        } else if (isFirst) {
            // First bid rejected by both — reset flag so onComplete handles no-fill.
            firstFillFired.set(false)
        }
    }

    override fun peek(): AuctionResult? =
        mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()

    override fun pop(): AuctionResult? = runBlocking {
        val result = mainCache.popFirst() ?: fallbackCache.popFirst()
        if (result != null) cancelAuction()
        result
    }

    override suspend fun poll(): AuctionResult {
        while (true) {
            val result = mainCache.popFirst() ?: fallbackCache.popFirst()
            if (result != null) {
                cancelAuction()
                return result
            }
            delay(100)
        }
    }

    override fun clear() {
        logInfo(TAG, "[$adTypeLabel] clear()")
        cancelAuction()
        scope.coroutineContext[Job]?.cancel()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: uses TwoLevelCacheConfig from server extras.
    }

    private fun cancelAuction() {
        auctionJob?.cancel()
        auctionJob = null
    }

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
                    status = RoundStatus.Win.code,
                    ext = adUnit?.extra?.toString(),
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
