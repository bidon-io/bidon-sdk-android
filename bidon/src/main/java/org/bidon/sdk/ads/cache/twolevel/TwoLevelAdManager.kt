package org.bidon.sdk.ads.cache.twolevel

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import kotlin.coroutines.coroutineContext

/**
 * Two-Level Cache AdCache facade per AD_CACHE_SPEC.md.
 */
internal class TwoLevelAdManager(
    override val demandAd: DemandAd,
    private val mainCache: CacheStorage,
    private val fallbackCache: FallbackCacheStorage,
    private val controller: TwoLevelAuctionController,
) : AdCache {

    /**
     * Auction lifecycle state. Tracks whether an auction is currently running
     * and holds a reference to the active [Job] for cancellation.
     */
    private sealed interface AuctionState {
        data object Idle : AuctionState
        data class Running(val job: Job) : AuctionState
    }

    private val adTypeLabel = demandAd.adType.code.uppercase()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _auctionState = MutableStateFlow<AuctionState>(AuctionState.Idle)

    /** Timestamp of last auction completion. Used by [pool.ManagerPool] for idle-time cleanup. */
    @Volatile
    var lastActiveAt: Long = SystemClock.elapsedRealtime()

    fun isIdle(): Boolean = _auctionState.value is AuctionState.Idle

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
        // Step 1: Main cache hit — instant delivery
        val mainState = mainCache.state.value
        if (mainState.hasContent) {
            val warmMain = mainState.head!!
            logInfo(TAG, "[$adTypeLabel] Cache hit from Main: ${warmMain.adSource.getStats().demandId.demandId}")
            val info = buildSyntheticAuctionInfo(warmMain)
            withContext(Dispatchers.Main) { onSuccess(warmMain, info) }
            return
        }

        // Step 2: Atomically transition Idle → Running
        val currentJob = coroutineContext[Job]!!
        val myState = AuctionState.Running(currentJob)
        if (!_auctionState.compareAndSet(AuctionState.Idle, myState)) {
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
                    val ms = mainCache.state.value
                    val fs = fallbackCache.state.value
                    val canAcceptMain = !ms.isFull && (ms.thresholdBar == null || ecpm >= ms.thresholdBar)
                    val canAcceptFallback = !fallbackCache.isDisabled &&
                        (!fs.isFull || ecpm > (fs.cheapestPrice ?: 0.0))
                    canAcceptMain || canAcceptFallback
                },
                onComplete = { auctionInfo, error ->
                    if (!firstFillFired.get()) {
                        // No bid was delivered — try Fallback rescue
                        val fallbackHead = fallbackCache.state.value.head
                        if (fallbackHead != null) {
                            val info = auctionInfo ?: buildSyntheticAuctionInfo(fallbackHead)
                            withContext(Dispatchers.Main) { onSuccess(fallbackHead, info) }
                        } else {
                            val failError = error ?: BidonError.NoFill(DemandId("auction"))
                            withContext(Dispatchers.Main) { onFailure(auctionInfo, failError) }
                        }
                    }
                },
            )
        } finally {
            // Only reset to Idle if we are still the active Running state.
            // Prevents overwriting a new Running state started after cancelAuction().
            _auctionState.compareAndSet(myState, AuctionState.Idle)
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
        if (!mainCache.state.value.isFull) {
            val mainResult = mainCache.insert(winner, sticky = isFirst)
            if (mainResult.isInserted) {
                if (isFirst) {
                    logInfo(TAG, "[$adTypeLabel] WIN: $demandId price=$price (sticky)")
                    val info = buildSyntheticAuctionInfo(winner)
                    withContext(Dispatchers.Main) { onSuccess(winner, info) }
                } else {
                    winner.adSource.markFillFinished(RoundStatus.Cached, price)
                    logInfo(TAG, "[$adTypeLabel] CACHE (Main): $demandId price=$price")
                }
                return
            }
            logInfo(TAG, "[$adTypeLabel] Main rejected: $demandId mainResult=$mainResult")
        }

        // Main didn't accept → Fallback
        if (fallbackCache.isDisabled) {
            winner.adSource.markFillFinished(RoundStatus.Lose, price)
            winner.adSource.destroy()
            logInfo(TAG, "[$adTypeLabel] LOSE (Fb disabled): $demandId price=$price")
            if (isFirst) firstFillFired.set(false)
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

        if (isFirst && fbResult.isInserted) {
            logInfo(TAG, "[$adTypeLabel] WIN (from Fallback): $demandId price=$price")
            val info = buildSyntheticAuctionInfo(winner)
            withContext(Dispatchers.Main) { onSuccess(winner, info) }
        } else if (isFirst) {
            firstFillFired.set(false)
        }
    }

    override fun peek(): AuctionResult? =
        mainCache.state.value.head ?: fallbackCache.state.value.head

    override fun pop(): AuctionResult? {
        // Fast-path: check snapshots before entering runBlocking
        if (!mainCache.state.value.hasContent && !fallbackCache.state.value.hasContent) return null
        // Brief runBlocking for Mutex-based popFirst (sub-microsecond list operation)
        val result = runBlocking {
            mainCache.popFirst() ?: fallbackCache.popFirst()
        }
        if (result != null) cancelAuction()
        return result
    }

    override suspend fun poll(): AuctionResult {
        val hasContent = mainCache.state.combine(fallbackCache.state) { main, fallback ->
            main.hasContent || fallback.hasContent
        }
        while (true) {
            // Suspend until either cache has content (event-driven, no polling delay)
            hasContent.first { it }
            val result = mainCache.popFirst() ?: fallbackCache.popFirst()
            if (result != null) {
                cancelAuction()
                return result
            }
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
        val current = _auctionState.value
        if (current is AuctionState.Running) {
            current.job.cancel()
            _auctionState.compareAndSet(current, AuctionState.Idle)
        }
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
