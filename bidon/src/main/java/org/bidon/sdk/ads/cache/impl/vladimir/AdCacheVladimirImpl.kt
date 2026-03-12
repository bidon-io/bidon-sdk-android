package org.bidon.sdk.ads.cache.impl.vladimir

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.view.ViewGroup
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.SdkDispatchers
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.pow

/**
 * V4 implementation of AdCache with two-slot caching.
 *
 * Load flow: Auction at caller-provided pricefloor, reuses stored RTB tokens,
 * excludes cached networks, fills empty slots. On the first load only, a 10s timer
 * enables preferRtb mode (skipping CPM units) if slot1 is still empty.
 *
 * Delegates slot management to [CacheSlotManager], auction mechanics to [WaterfallLoader],
 * token storage to [RtbTokenStore], and cross-instance persistence to [CachePersistedState].
 */
internal class AdCacheVladimirImpl(
    override val demandAd: DemandAd,
) : AdCache {

    private enum class LoadingState { IDLE, LOADING }

    private val persistedState = CachePersistedState.getState(demandAd.adType)

    private val scope = CoroutineScope(SdkDispatchers.Main + SupervisorJob())
    private val slots = CacheSlotManager(scope)
    private val loader = WaterfallLoader(demandAd)
    private val tokenStore = RtbTokenStore(persistedState.rtbTokens)

    // Strategy state (persisted across instance recreations via CachePersistedState)
    private var isFirstLoad = !persistedState.firstLoadCompleted

    init {
        persistedState.restoreInto(slots)
        slots.onSlotVacancy = ::onSlotVacancy
        logInfo(TAG, "init: adType=${demandAd.adType}, slots=${slots.description()}, isFirstLoad=$isFirstLoad")
    }

    // Loading guards
    private val loadingState = MutableStateFlow(LoadingState.IDLE)
    private val callbackFired = AtomicBoolean(false)
    private var loadingJob: Job? = null
    private var autoRestartJob: Job? = null
    private var retryAttempt = 0
    private var lastAdTypeParam: AdTypeParam? = null

    override fun withSettings(settings: Cacheable.Settings) {
        // TODO: not implemented yet — two-slot design is hardcoded
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(
            TAG,
            "cache(): pricefloor=${adTypeParam.pricefloor}, slots=${slots.description()}, isFirstLoad=$isFirstLoad, " +
                "loadingState=${loadingState.value}, retryAttempt=$retryAttempt"
        )
        slots.logCacheStatus("cache() entry")
        lastAdTypeParam = adTypeParam

        // Fire onSuccess immediately if we already have a cached ad at or above the price floor
        val cachedResult = slots.peek()
        val cachedInfo = slots.peekAuctionInfo()
        val cachedPrice = cachedResult?.price ?: 0.0
        val meetsFloor = cachedResult != null && cachedPrice >= adTypeParam.pricefloor
        if (meetsFloor && cachedInfo != null) {
            logInfo(TAG, "cache(): ad available at $cachedPrice >= floor ${adTypeParam.pricefloor}, firing immediate onSuccess (auctionId=${cachedInfo.auctionId})")
            adTypeParam.activity.runOnUiThread {
                onSuccess(cachedResult!!, cachedInfo)
            }
        } else if (cachedResult != null) {
            logInfo(TAG, "cache(): ad available at $cachedPrice < floor ${adTypeParam.pricefloor}, skipping immediate onSuccess")
        }

        // Evict slot2 if both slots are full but both below the requested floor.
        // This frees a slot so loading can try to find an ad that meets the floor.
        if (slots.isFull() && (slots.primaryPrice ?: 0.0) < adTypeParam.pricefloor) {
            logInfo(TAG, "cache(): EVICTION — both slots below floor ${adTypeParam.pricefloor}, destroying backup")
            slots.evictBackup()
        }

        // If both slots are full, no loading needed
        if (slots.isFull()) {
            logInfo(TAG, "cache(): both slots filled, no loading needed")
            return
        }

        // Atomic: only one thread can transition IDLE → LOADING
        val wasAlreadyLoading = loadingState.getAndUpdate { LoadingState.LOADING } == LoadingState.LOADING
        if (wasAlreadyLoading) {
            logInfo(TAG, "cache(): SKIPPED — already loading (atomic guard)")
            return
        }

        if (autoRestartJob != null) {
            logInfo(TAG, "cache(): cancelling pending auto-restart")
            autoRestartJob?.cancel()
            autoRestartJob = null
        }

        // If onSuccess was already fired above (ad met the floor), mark it so loading doesn't fire it again
        callbackFired.set(meetsFloor)
        logInfo(TAG, "cache(): state→LOADING, callbackFired=$meetsFloor, launching load")

        loadingJob = scope.launch {
            runCatching {
                runLoad(adTypeParam, onSuccess, onFailure)
            }.onFailure { cause ->
                if (cause is CancellationException) {
                    logInfo(TAG, "cache(): loading cancelled (expected during clear)")
                    return@onFailure
                }
                logError(TAG, "cache(): auction FAILED with exception", cause)
                loadingState.value = LoadingState.IDLE
                if (callbackFired.compareAndSet(false, true)) {
                    logInfo(TAG, "cache(): firing onFailure callback (exception)")
                    onFailure(null, cause)
                }
                scheduleAutoRestart(adTypeParam)
            }
        }
    }

    override fun peek(): AuctionResult? {
        val result = slots.peek()
        logInfo(TAG, "peek(): ${result?.demandId ?: "null"}")
        return result
    }

    override fun pop(): AuctionResult? {
        slots.logCacheStatus("pop() before")
        val result = slots.pop()
        if (result != null) {
            logInfo(TAG, "pop(): popped ${result.demandId} @ ${result.price}")

            // Banner views may get re-parented by ad network SDKs during Activity lifecycle.
            // Detach the view from any internal SDK container before returning to caller.
            (result.adSource as? AdSource.Banner<*>)?.getAdView()?.networkAdview?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }

            // Remove the shown ad's token — it was consumed and cannot produce a valid bid again
            tokenStore.removeToken(result.demandId)

            persistedState.snapshotOnPop(slots)
        } else {
            logInfo(TAG, "pop(): nothing to pop")
        }
        slots.logCacheStatus("pop() after")
        return result
    }

    override suspend fun poll(): AuctionResult {
        logInfo(TAG, "poll(): suspending until ad available...")
        while (true) {
            slots.awaitAvailable()
            val result = pop()
            if (result != null) {
                logInfo(TAG, "poll(): got ${result.demandId} @ ${result.price}")
                return result
            }
            logInfo(TAG, "poll(): slot expired before pop, retrying...")
        }
    }

    override fun clear() {
        logInfo(TAG, "clear(): slots=${slots.description()}, loadingState=${loadingState.value}")
        retryAttempt = 0
        lastAdTypeParam = null
        autoRestartJob?.cancel()
        autoRestartJob = null
        loadingJob?.cancel()
        loadingJob = null

        // Extract ads without destroying — they'll be preserved for the next instance.
        // The host app destroys and recreates the cache instance on every show cycle.
        // Preserving ads lets the next instance serve them immediately without re-loading.
        val extracted = slots.extractAll()
        persistedState.preserveOnClear(extracted)

        if (loadingState.getAndUpdate { LoadingState.IDLE } == LoadingState.LOADING) {
            logInfo(TAG, "clear(): loading was in progress, cancelled")
        }
        logInfo(TAG, "clear(): done")
    }

    // --- Load ---

    private suspend fun runLoad(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        val pricefloor = adTypeParam.pricefloor
        logInfo(
            TAG,
            "runLoad(): pricefloor=$pricefloor, isFirstLoad=$isFirstLoad, " +
                "slots=${slots.description()}, timeout=${GLOBAL_TIMEOUT_MS}ms"
        )

        // Capture and mark first load
        val isFirstLoadRun = isFirstLoad
        if (isFirstLoadRun) {
            isFirstLoad = false
            persistedState.firstLoadCompleted = true
        }

        var round: WaterfallLoader.AuctionRound? = null
        var loadIndex = 0

        // Get valid (non-expired) tokens — removes expired ones automatically
        val validTokens = tokenStore.getValidTokens()
        logInfo(TAG, "runLoad(): ${validTokens.size} valid RTB tokens: [${validTokens.keys.joinToString()}]")

        // 10s timer (first load only): enables preferRtb mode if slot1 is still empty
        val preferRtb = if (isFirstLoadRun) AtomicBoolean(false) else null
        val timerJob = if (isFirstLoadRun) {
            scope.launch {
                delay(LOADING_TIMEOUT_MS)
                if (slots.peek() == null) {
                    logInfo(TAG, "runLoad(): TIMEOUT — slot1 empty, enabling preferRtb mode")
                    preferRtb?.set(true)
                }
            }
        } else {
            null
        }

        val timedOut = withTimeoutOrNull(GLOBAL_TIMEOUT_MS) {
            round = loader.startRound(
                adTypeParam = adTypeParam,
                pricefloor = pricefloor,
                existingTokens = validTokens,
                excludedDemandIds = slots.cachedDemandIds,
            )
            val currentRound = round!!
            logWaterfall("Load", currentRound.adUnits)
            slots.logCacheStatus("Load start")

            var fillCount = 0
            var skipCount = 0
            for (adUnit in currentRound.adUnits) {
                if (slots.isFull()) {
                    logInfo(TAG, "Load: BREAK at [$loadIndex] — both slots filled")
                    break
                }

                loadIndex++

                // preferRtb: skip CPM units to reach RTB faster
                if (preferRtb?.get() == true && adUnit.bidType == BidType.CPM) {
                    skipCount++
                    continue
                }

                // Skip units from networks already cached — avoids duplicate networks in slots
                if (adUnit.demandId in slots.cachedDemandIds) {
                    skipCount++
                    continue
                }
                val result = loader.loadUnit(adUnit, currentRound)
                if (result?.roundStatus == RoundStatus.Successful) {
                    fillCount++
                    logInfo(TAG, "Load: [$loadIndex] ✓ FILL from ${result.demandId} @ ${result.price}")
                    handleFill(result, currentRound)

                    // Add to stats collector: first fill keeps Successful (becomes round winner),
                    // subsequent fills get Win status so AuctionStatImpl won't downgrade them to LOSE.
                    if (fillCount == 1) {
                        loader.addToCollector(result)
                    } else {
                        loader.addToCollector(result.withRoundStatus(RoundStatus.Win))
                        logInfo(TAG, "Load: [$loadIndex] added ${result.demandId} to collector as WIN (slot2)")
                    }

                    if (preferRtb?.get() == true) {
                        logInfo(TAG, "Load: preferRtb fill — abandoning waterfall")
                        break
                    }
                } else {
                    // Add failures to stats collector as-is
                    if (result != null) {
                        loader.addToCollector(result)
                    }
                    logInfo(TAG, "Load: [$loadIndex] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
                    // Destroy failed ad sources immediately to release adapter resources.
                    // Failed loads create internal SDK objects (e.g. DT Exchange spots in
                    // InneractiveAdSpotManager) that won't be cleaned up by GC alone.
                    // AuctionFailed has no real adSource (throws on access), so skip those.
                    if (result is AuctionResult.Network || result is AuctionResult.Bidding) {
                        result.adSource.destroy()
                        logInfo(TAG, "Load: [$loadIndex] destroyed failed adSource for ${adUnit.demandId}")
                    }
                }
            }
            logInfo(TAG, "Load: processed $loadIndex/${currentRound.adUnits.size} units, filled=$fillCount, skipped=$skipCount")

            if (fillCount == 0) {
                logInfo(TAG, "Load: all waterfall units failed, callback deferred to finalizeLoad()")
            }
        } == null

        if (timedOut) {
            logInfo(TAG, "runLoad(): TIMED OUT after ${GLOBAL_TIMEOUT_MS}ms (processed $loadIndex units)")
        }

        timerJob?.cancel()

        // If preferRtb filled, abandon this round and start fresh to fill slot 2
        if (preferRtb?.get() == true && slots.peek() != null && round != null) {
            logInfo(TAG, "runLoad(): preferRtb fill complete, starting fresh round for slot 2")
            storeRtbTokens(round!!)
            loader.collectStats(round!!)
            runLoad(adTypeParam, onSuccess, onFailure)
            return
        }

        logInfo(TAG, "runLoad(): waterfall done, collecting stats...")
        if (round != null) {
            // Store RTB tokens for future Load rounds
            storeRtbTokens(round!!)

            val roundStat = loader.collectStats(round!!)
            finalizeLoad(round!!, roundStat, adTypeParam, onSuccess, onFailure)
        } else {
            // Timed out before startRound completed — no round to finalize
            logInfo(TAG, "runLoad(): no round available (startRound timed out), scheduling auto-restart")
            loadingState.value = LoadingState.IDLE
            if (callbackFired.compareAndSet(false, true)) {
                logInfo(TAG, "runLoad(): FIRING onFailure callback (startRound timeout)")
                adTypeParam.activity.runOnUiThread { onFailure(null, BidonError.NoAuctionResults) }
            }
            scheduleAutoRestart(adTypeParam)
        }
    }

    // --- Bridge: Loader → Slots ---

    private fun handleFill(
        result: AuctionResult,
        round: WaterfallLoader.AuctionRound,
    ) {
        retryAttempt = 0
        // Build a preliminary AuctionInfo without roundStat for slot storage.
        // finalizeLoad() will replace it with the complete version once stats are collected.
        val preliminaryInfo = buildAuctionInfo(round.response)
        logInfo(TAG, "handleFill(): ${result.demandId} @ ${result.price}, slots before=${slots.description()}")

        val primaryUpdated = slots.insert(result, preliminaryInfo)
        logInfo(TAG, "handleFill(): ${result.demandId} → primaryUpdated=$primaryUpdated")
        slots.logCacheStatus("handleFill after insert")

        // Every cached ad will be shown — notify as winner at insert time
        notifyAsWinner(result, round.response.externalWinNotificationsEnabled)
        // Callback is NOT fired here — deferred to finalizeLoad() which has roundStat.
        // This ensures AuctionInfo.adUnits (network_responses) is populated for mediation reports.
    }

    // --- Finalization ---

    private fun finalizeLoad(
        round: WaterfallLoader.AuctionRound,
        roundStat: RoundStat?,
        originalAdTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(TAG, "finalizeLoad(): callbackFired=${callbackFired.get()}")
        slots.logCacheStatus("finalizeLoad")

        loadingState.value = LoadingState.IDLE
        logInfo(TAG, "finalizeLoad(): state→IDLE")

        val auctionInfo = buildAuctionInfo(round.response, roundStat)
        // Update the preliminary AuctionInfo stored in slots with the complete version
        // that includes roundStat (adUnits / noBids for network_responses in mediation reports).
        slots.updateAuctionInfo(auctionInfo)

        if (callbackFired.compareAndSet(false, true)) {
            if (slots.peek() != null) {
                logInfo(TAG, "finalizeLoad(): FIRING onSuccess callback, slots=${slots.description()}")
                originalAdTypeParam.activity.runOnUiThread { onSuccess(slots.peek()!!, auctionInfo) }
            } else {
                logInfo(TAG, "finalizeLoad(): FIRING onFailure callback (no fills)")
                originalAdTypeParam.activity.runOnUiThread { onFailure(auctionInfo, BidonError.NoAuctionResults) }
            }
        } else {
            logInfo(TAG, "finalizeLoad(): callback already fired, slots=${slots.description()}")
        }

        if (slots.slotCount < 2) {
            logInfo(TAG, "finalizeLoad(): slotCount=${slots.slotCount} < 2, scheduling auto-restart")
            scheduleAutoRestart(originalAdTypeParam)
        } else {
            retryAttempt = 0
            logInfo(TAG, "finalizeLoad(): both slots filled, retryAttempt reset to 0")
        }
    }

    // --- Win Notification ---

    private fun notifyAsWinner(result: AuctionResult, externalWinNotificationsEnabled: Boolean) {
        logInfo(TAG, "notifyAsWinner(): ${result.demandId} @ ${result.price}, externalWinNotifications=$externalWinNotificationsEnabled")
        result.adSource.markWin()

        // Send Bidon win HTTP request for all units inserted into cache slots
        val statsCollector = result.adSource as StatisticsCollector
        statsCollector.sendWin()
        // Mark as sent to prevent duplicate win/loss at show time
        statsCollector.markWinLoseNotificationsSent()
        logInfo(TAG, "notifyAsWinner(): sendWin() sent for ${result.demandId}")

        if (!externalWinNotificationsEnabled) {
            if (result !is AuctionResult.Bidding && result.adSource is WinLossNotifiable) {
                (result.adSource as WinLossNotifiable).notifyWin()
                logInfo(TAG, "notifyAsWinner(): notifyWin() sent to ${result.demandId}")
            }
        }
    }

    // --- RTB Token Storage ---

    private fun storeRtbTokens(round: WaterfallLoader.AuctionRound) {
        val noBidDemandIds = round.response.noBids?.map { it.demandId }?.toSet() ?: emptySet()
        tokenStore.storeFromRound(round.adUnits, noBidDemandIds, round.tokens)
    }

    // --- Slot Vacancy ---

    /**
     * Called by [CacheSlotManager] when a slot becomes empty due to expiration.
     * Triggers cache replenishment if not already loading.
     */
    private fun onSlotVacancy() {
        val adTypeParam = lastAdTypeParam
        if (adTypeParam == null) {
            logInfo(TAG, "onSlotVacancy(): no adTypeParam available, skipping replenishment")
            return
        }
        if (loadingState.value == LoadingState.LOADING) {
            logInfo(TAG, "onSlotVacancy(): already loading, skipping replenishment")
            return
        }
        if (autoRestartJob != null) {
            logInfo(TAG, "onSlotVacancy(): auto-restart already scheduled, skipping")
            return
        }
        logInfo(TAG, "onSlotVacancy(): slot vacancy detected, scheduling replenishment")
        scheduleAutoRestart(adTypeParam)
    }

    // --- Auto Restart ---

    private fun scheduleAutoRestart(adTypeParam: AdTypeParam) {
        retryAttempt++
        val delaySec = 2.0.pow(min(6, retryAttempt).toDouble()).toLong()
        val delayMs = delaySec * 1000L
        logInfo(TAG, "scheduleAutoRestart(): attempt=$retryAttempt, delay=${delaySec}s (${delayMs}ms)")
        autoRestartJob = scope.launch {
            logInfo(TAG, "scheduleAutoRestart(): waiting ${delaySec}s...")
            delay(delayMs)
            logInfo(TAG, "scheduleAutoRestart(): delay elapsed, calling cache()")
            cache(adTypeParam, onSuccess = { _, _ -> }, onFailure = { _, _ -> })
        }
    }

    // --- Summary Logs ---

    private fun logWaterfall(label: String, adUnits: List<AdUnit>) {
        logInfo(TAG, "── $label | Waterfall: ${adUnits.size} units ──")
        adUnits.forEachIndexed { index, unit ->
            logInfo(TAG, "  #${index + 1}  ${unit.demandId} / ${unit.bidType} / ${unit.pricefloor}")
        }
        if (adUnits.isEmpty()) {
            logInfo(TAG, "  (no units)")
        }
    }

    // --- Helpers ---

    private fun buildAuctionInfo(
        auctionResponse: AuctionResponse,
        roundStat: RoundStat? = null,
    ): AuctionInfo {
        return AuctionInfo(
            auctionId = auctionResponse.auctionId,
            auctionConfigurationId = auctionResponse.auctionConfigurationId,
            auctionConfigurationUid = auctionResponse.auctionConfigurationUid,
            auctionPricefloor = auctionResponse.pricefloor,
            auctionTimeout = auctionResponse.auctionTimeout,
            noBids = roundStat?.noBids?.map { it.toAuctionNoBidInfo() },
            adUnits = roundStat?.demands?.map { it.toAuctionInfo() },
        )
    }
}

/**
 * Creates a new [AuctionResult] with the given [roundStatus], preserving the same adSource.
 * Used to override the immutable roundStatus for stats reporting (e.g. marking cached slot2 as Win).
 */
private fun AuctionResult.withRoundStatus(roundStatus: RoundStatus): AuctionResult = when (this) {
    is AuctionResult.Network -> AuctionResult.Network(adSource = adSource, roundStatus = roundStatus)
    is AuctionResult.Bidding -> AuctionResult.Bidding(adSource = adSource, roundStatus = roundStatus)
    is AuctionResult.AuctionFailed -> this // Failed results should not be re-wrapped
}

private const val TAG = "AdCacheVladimir"
private const val LOADING_TIMEOUT_MS = 10_000L
private const val GLOBAL_TIMEOUT_MS = 29_000L
