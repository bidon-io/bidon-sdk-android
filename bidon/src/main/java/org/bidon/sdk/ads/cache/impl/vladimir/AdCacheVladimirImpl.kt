package org.bidon.sdk.ads.cache.impl.vladimir

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
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
 * V4 implementation of AdCache with two-slot caching, background waterfall continuation,
 * and show fallback.
 *
 * Load flow: Auction at caller-provided pricefloor, reuses stored RTB tokens,
 * excludes cached networks, fills empty slots. On the first load only, a 10s timer
 * enables preferRtb mode (skipping CPM units) if slot1 is still empty.
 * Walks remaining units from previous waterfalls.
 *
 * Delegates slot management to [CacheSlotManager], auction mechanics to [WaterfallLoader],
 * token storage to [RtbTokenStore], show fallback to [ShowFallbackHandler],
 * and cross-instance persistence to [CachePersistedState].
 */
internal class AdCacheVladimirImpl(
    override val demandAd: DemandAd,
    @Suppress("unused") private val resolver: AuctionResolver,
) : AdCache {

    private enum class LoadingState { IDLE, LOADING }

    private val persistedState = CachePersistedState.getState(demandAd.adType)

    private val scope = CoroutineScope(SdkDispatchers.Main + SupervisorJob())
    private val slots = CacheSlotManager(scope)
    private val loader = WaterfallLoader(demandAd)
    private val tokenStore = RtbTokenStore(persistedState.rtbTokens)
    private val fallbackHandler = ShowFallbackHandler(scope, slots)

    // Strategy state (persisted across instance recreations via CachePersistedState)
    private var isFirstLoad = !persistedState.firstLoadCompleted

    // Remaining units from a previous waterfall to try after the current round.
    // Persists across rounds: untried units carry forward.
    private val remainingUnits = mutableListOf<AdUnit>()
    private var remainingRound: WaterfallLoader.AuctionRound? = null

    init {
        remainingRound = persistedState.restoreInto(slots, remainingUnits)
    }

    // Loading guards
    private val loadingState = MutableStateFlow(LoadingState.IDLE)
    private val callbackFired = AtomicBoolean(false)
    private var loadingJob: Job? = null
    private var autoRestartJob: Job? = null
    private var retryAttempt = 0

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: V4 does not use cache settings yet
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(
            TAG,
            "cache(): slots=${slots.description()}, isFirstLoad=$isFirstLoad, " +
                "loadingState=${loadingState.value}, retryAttempt=$retryAttempt"
        )
        slots.logCacheStatus("cache() entry")

        // Fire onSuccess immediately if we already have a cached ad
        val cachedResult = slots.peek()
        val hasAd = cachedResult != null
        if (cachedResult != null) {
            logInfo(TAG, "cache(): ad available, firing immediate onSuccess")
            adTypeParam.activity.runOnUiThread {
                onSuccess(cachedResult, AuctionInfo(auctionId = "", auctionConfigurationId = null, auctionConfigurationUid = null, auctionPricefloor = 0.0, auctionTimeout = 0L, noBids = null, adUnits = null))
            }
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

        // If onSuccess was already fired above, mark it so loading doesn't fire it again
        callbackFired.set(hasAd)
        fallbackHandler.lastActivity = adTypeParam.activity
        logInfo(TAG, "cache(): state→LOADING, callbackFired=$hasAd, launching load")

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

            fallbackHandler.observe(result)
            persistedState.snapshotOnPop(slots)
        } else {
            logInfo(TAG, "pop(): nothing to pop")
        }
        slots.logCacheStatus("pop() after")
        return result
    }

    override suspend fun poll(): AuctionResult {
        logInfo(TAG, "poll(): suspending until ad available...")
        val result = slots.poll()
        logInfo(TAG, "poll(): got ${result.demandId} @ ${result.price}")

        fallbackHandler.observe(result)

        return result
    }

    override fun clear() {
        logInfo(TAG, "clear(): slots=${slots.description()}, loadingState=${loadingState.value}")
        retryAttempt = 0
        autoRestartJob?.cancel()
        autoRestartJob = null
        loadingJob?.cancel()
        loadingJob = null

        val preserved = slots.extractAll()
        persistedState.preserveOnClear(preserved, remainingUnits, remainingRound)

        remainingUnits.clear()
        remainingRound = null
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

        // Evict slot2 if both slots full but both below requested floor
        if (slots.isFull() && (slots.primaryPrice ?: 0.0) < pricefloor) {
            logInfo(TAG, "runLoad(): EVICTION — both slots below floor $pricefloor, destroying backup")
            slots.evictBackup()
        }

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
                loadIndex++

                if (slots.isFull()) {
                    logInfo(TAG, "Load: BREAK at [$loadIndex] — both slots filled")
                    break
                }

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
                    handleFill(result, currentRound, adTypeParam, onSuccess)
                    if (preferRtb?.get() == true) {
                        logInfo(TAG, "Load: preferRtb fill — abandoning waterfall")
                        break
                    }
                }
            }
            logInfo(TAG, "Load: processed $loadIndex/${currentRound.adUnits.size} units, filled=$fillCount, skipped=$skipCount")

            // Save untried units from this Load waterfall for future rounds.
            // These are cheaper units the server returned but we didn't walk
            // (because slots filled early). They carry forward as remainingUnits.
            // When preferRtb is active, the waterfall is incomplete (CPM units were skipped),
            // so remaining units are not saved — the next Load starts a fresh round.
            if (loadIndex < currentRound.adUnits.size && preferRtb?.get() != true) {
                val untried = currentRound.adUnits.drop(loadIndex)
                logInfo(TAG, "Load: saving ${untried.size} untried units for future rounds")
                remainingUnits.addAll(0, untried) // prepend — newer waterfall units are higher priority
                remainingRound = currentRound
            }

            // Walk remaining units from previous rounds only when both slots are empty
            if (slots.peek() == null && remainingUnits.isNotEmpty()) {
                walkRemainingUnits(adTypeParam, onSuccess)
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
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        retryAttempt = 0
        logInfo(TAG, "handleFill(): ${result.demandId} @ ${result.price}, slots before=${slots.description()}, callbackFired=${callbackFired.get()}")

        val primaryUpdated = slots.insert(result)
        logInfo(TAG, "handleFill(): ${result.demandId} → primaryUpdated=$primaryUpdated")
        slots.logCacheStatus("handleFill after insert")

        if (primaryUpdated && callbackFired.compareAndSet(false, true)) {
            logInfo(TAG, "handleFill(): FIRING onSuccess callback for ${result.demandId} @ ${result.price}")
            val info = buildAuctionInfo(round.response)
            adTypeParam.activity.runOnUiThread { onSuccess(result, info) }
        } else if (primaryUpdated) {
            logInfo(TAG, "handleFill(): HOT-SWAP — onExpired already emitted by slot manager, caller will call cache() to get new primary")
        }
    }

    // --- Remaining Units ---

    /**
     * Walks remaining units from a previous waterfall to fill empty slots.
     * Remaining units are cheaper ad units that were never tried because a previous
     * round stopped early (e.g., slots filled). Refreshes expired RTB tokens before loading.
     *
     * Called after Load waterfall if slots are still not full.
     */
    private suspend fun walkRemainingUnits(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        val round = remainingRound ?: return
        if (remainingUnits.isEmpty()) return

        logInfo(TAG, "── Remaining | ${remainingUnits.size} units from previous waterfall ──")

        // Refresh expired RTB tokens before walking
        val rtbDemandIds = remainingUnits
            .filter { it.bidType == BidType.RTB }
            .map { it.demandId }
            .toSet()

        val tokens = tokenStore.refreshExpired(rtbDemandIds) {
            loader.fetchTokens(adTypeParam)
        }

        var index = 0
        var fillCount = 0
        var skipCount = 0
        val iterator = remainingUnits.iterator()
        while (iterator.hasNext()) {
            if (slots.isFull()) break
            val adUnit = iterator.next()
            iterator.remove()
            index++

            // Skip units from networks already cached — avoids duplicate networks in slots
            if (adUnit.demandId in slots.cachedDemandIds) {
                skipCount++
                continue
            }

            val result = loader.loadUnit(adUnit, round, tokens)
            if (result?.roundStatus == RoundStatus.Successful) {
                fillCount++
                logInfo(TAG, "Remaining: [$index] ✓ FILL from ${result.demandId} @ ${result.price}")
                handleFill(result, round, adTypeParam, onSuccess)
            }
        }
        logInfo(TAG, "Remaining: processed $index units, filled=$fillCount, skipped=$skipCount, ${remainingUnits.size} still remaining")
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

        notifyWinner(round.response.externalWinNotificationsEnabled)
        loadingState.value = LoadingState.IDLE
        logInfo(TAG, "finalizeLoad(): state→IDLE")

        val auctionInfo = buildAuctionInfo(round.response, roundStat)
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

    private fun notifyWinner(externalWinNotificationsEnabled: Boolean) {
        val winner = slots.peek()
        if (winner == null) {
            logInfo(TAG, "notifyWinner(): no winner (slot1 empty)")
            return
        }
        logInfo(TAG, "notifyWinner(): winner=${winner.demandId} @ ${winner.price}, externalWinNotifications=$externalWinNotificationsEnabled")

        winner.adSource.markWin()
        logInfo(TAG, "notifyWinner(): markWin() called on ${winner.demandId}")

        if (!externalWinNotificationsEnabled) {
            if (winner !is AuctionResult.Bidding && winner.adSource is WinLossNotifiable) {
                (winner.adSource as WinLossNotifiable).notifyWin()
                logInfo(TAG, "notifyWinner(): notifyWin() sent to ${winner.demandId}")
            } else {
                logInfo(TAG, "notifyWinner(): skipped notifyWin() (isBidding=${winner is AuctionResult.Bidding}, isWinLossNotifiable=${winner.adSource is WinLossNotifiable})")
            }
        }
    }

    // --- RTB Token Storage ---

    private fun storeRtbTokens(round: WaterfallLoader.AuctionRound) {
        val noBidDemandIds = round.response.noBids?.map { it.demandId }?.toSet() ?: emptySet()
        tokenStore.storeFromRound(round.adUnits, noBidDemandIds, round.tokens)
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

private const val TAG = "AdCacheVladimir"
private const val LOADING_TIMEOUT_MS = 10_000L
private const val GLOBAL_TIMEOUT_MS = 29_000L
