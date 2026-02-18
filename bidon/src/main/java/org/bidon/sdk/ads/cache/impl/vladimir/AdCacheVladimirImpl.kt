package org.bidon.sdk.ads.cache.impl.vladimir

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AdType
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
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
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
 * Delegates slot management to [CacheSlotManager] and auction mechanics to [WaterfallLoader].
 */
internal class AdCacheVladimirImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
) : AdCache {

    /**
     * Stored RTB token with expiration tracking.
     * Each network's token expires independently after [RTB_TOKEN_EXPIRATION_MS].
     */
    private data class StoredToken(
        val tokenInfo: TokenInfo,
        val storedAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now - storedAt > RTB_TOKEN_EXPIRATION_MS
    }

    companion object {
        /**
         * Strategy state that persists across instance recreations within the same process.
         * Appodeal calls clear() + creates new cache on every show cycle, so we preserve
         * discovery flag, RTB tokens, and price context to avoid redundant work.
         * Keyed by [AdType] since each ad type has independent caching lifecycle.
         */
        private class PersistedState {
            var firstLoadCompleted: Boolean = false
            val rtbTokens: MutableMap<String, StoredToken> = mutableMapOf()
            val preservedAds: MutableList<AuctionResult> = mutableListOf()
            var preservedAdsConsumed: Boolean = false

            /** True when preservedAds were saved by pop() snapshot (not by clear()). */
            var preservedByPop: Boolean = false
            val preservedRemainingUnits: MutableList<AdUnit> = mutableListOf()
            var preservedRemainingRound: WaterfallLoader.AuctionRound? = null
        }

        private val stateByAdType = mutableMapOf<AdType, PersistedState>()

        private fun getState(adType: AdType): PersistedState =
            stateByAdType.getOrPut(adType) { PersistedState() }
    }

    private enum class LoadingState { IDLE, LOADING }

    private val persistedState = getState(demandAd.adType)

    private val scope = CoroutineScope(SdkDispatchers.Main + SupervisorJob())
    private val slots = CacheSlotManager(scope)
    private val loader = WaterfallLoader(demandAd)

    // Strategy state (persisted across instance recreations via companion)
    private var isFirstLoad = !persistedState.firstLoadCompleted
    private val storedRtbTokens: MutableMap<String, StoredToken> get() = persistedState.rtbTokens

    // Remaining units from a previous waterfall to try after the current round.
    // Persists across rounds: untried units carry forward.
    private val remainingUnits = mutableListOf<AdUnit>()
    private var remainingRound: WaterfallLoader.AuctionRound? = null

    init {
        // Restore preserved ads from previous instance (saved during clear() or pop() snapshot)
        val preserved = persistedState.preservedAds.toList()
        if (preserved.isNotEmpty()) {
            persistedState.preservedAds.clear()
            // Only mark consumed when ads came from pop() snapshot.
            // Ads from clear() preserve are NOT pop-snapshots — the new instance
            // must allow its own clear() to re-preserve them normally.
            if (persistedState.preservedByPop) {
                persistedState.preservedAdsConsumed = true
            }
            persistedState.preservedByPop = false
            for (ad in preserved) {
                slots.insert(ad)
            }
            logInfo(TAG, "init: restored ${preserved.size} preserved ads → ${slots.description()}")
        }

        // Restore remaining units from previous instance
        val savedUnits = persistedState.preservedRemainingUnits.toList()
        val savedRound = persistedState.preservedRemainingRound
        if (savedUnits.isNotEmpty() && savedRound != null) {
            remainingUnits.addAll(savedUnits)
            remainingRound = savedRound
            persistedState.preservedRemainingUnits.clear()
            persistedState.preservedRemainingRound = null
            logInfo(TAG, "init: restored ${savedUnits.size} remaining units from previous instance")
        }
    }

    // Loading guards
    private val loadingState = MutableStateFlow(LoadingState.IDLE)
    private val callbackFired = AtomicBoolean(false)
    private var loadingJob: Job? = null
    private var autoRestartJob: Job? = null
    private var settings: Cacheable.Settings = Cacheable.DefaultSettings
    private var retryAttempt = 0
    private var lastActivity: android.app.Activity? = null

    @Suppress("unused")
    private val regulation: Regulation by lazy { get() }

    override fun withSettings(settings: Cacheable.Settings) {
        logInfo(TAG, "withSettings(): minCacheSize=${settings.minCacheSize}")
        this.settings = settings
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
        val hasAd = slots.peek() != null
        if (hasAd) {
            logInfo(TAG, "cache(): ad available, firing immediate onSuccess")
            val cachedResult = slots.peek()!!
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
        lastActivity = adTypeParam.activity // Store for show fallback
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
        logInfo(TAG, "peek(): ${result?.adSource?.getStats()?.demandId?.demandId ?: "null"}")
        return result
    }

    override fun pop(): AuctionResult? {
        slots.logCacheStatus("pop() before")
        val result = slots.pop()
        if (result != null) {
            val price = result.adSource.getStats().price
            logInfo(TAG, "pop(): popped ${result.adSource.getStats().demandId.demandId} @ $price")

            // Observe for ShowFailed to enable automatic fallback to backup ad
            observeShowFallback(result)

            // Eagerly preserve remaining ads for next instance.
            // Protects against new instance creation before clear() is called.
            val remaining = slots.snapshotAll()
            persistedState.preservedAds.clear()
            persistedState.preservedAds.addAll(remaining)
            persistedState.preservedAdsConsumed = false
            persistedState.preservedByPop = true
            logInfo(TAG, "pop(): snapshot ${remaining.size} remaining ads to persisted state")
        } else {
            logInfo(TAG, "pop(): nothing to pop")
        }
        slots.logCacheStatus("pop() after")
        return result
    }

    /**
     * Observes the popped ad for ShowFailed events and automatically attempts fallback.
     *
     * When show() fails on the primary ad, we automatically try to show the backup ad
     * from slot2 (now in slot1 after pop promoted it). The backup's events are forwarded
     * to the primary's event flow.
     *
     * ## Event flow to caller:
     * - If primary succeeds: Shown → Closed (normal flow)
     * - If primary fails, backup succeeds: ShowFailed → Shown → Closed
     * - If both fail: ShowFailed → ShowFailed
     *
     * Note: The initial ShowFailed from primary cannot be suppressed without modifying
     * AdSource outside the vladimir package. Callers may see ShowFailed followed by Shown
     * when fallback succeeds - this indicates primary failed but backup recovered.
     *
     * This implements: "When the winner show failed, we try to show the second cache.
     * Only if it failed, we return onFailed."
     */
    private fun observeShowFallback(result: AuctionResult) {
        val source = result.adSource
        val primaryDemandId = source.demandId.demandId
        var fallbackAttempted = false

        source.adEvent.onEach { event ->
            if (event is AdEvent.ShowFailed && !fallbackAttempted) {
                fallbackAttempted = true // Prevent double-fallback if event emitted multiple times
                logInfo(TAG, "ShowFailed on $primaryDemandId, trying backup...")

                val backup = slots.pop()
                if (backup != null) {
                    val backupSource = backup.adSource
                    val backupDemandId = backupSource.demandId.demandId
                    logInfo(TAG, "Found backup ad $backupDemandId, attempting show...")

                    // Forward backup events to primary's flow so caller sees outcome
                    backupSource.adEvent.onEach { backupEvent ->
                        logInfo(TAG, "Forwarding backup event $backupEvent from $backupDemandId to primary flow")
                        source.emitEvent(backupEvent)
                    }.launchIn(scope)

                    // Show backup using stored activity from cache() call
                    val activity = lastActivity
                    if (activity != null) {
                        when (backupSource) {
                            is AdSource.Interstitial<*> -> {
                                logInfo(TAG, "Showing backup interstitial $backupDemandId")
                                backupSource.show(activity)
                            }
                            is AdSource.Rewarded<*> -> {
                                logInfo(TAG, "Showing backup rewarded $backupDemandId")
                                backupSource.show(activity)
                            }
                            else -> {
                                logInfo(TAG, "Backup $backupDemandId is not Interstitial or Rewarded, cannot show")
                            }
                        }
                    } else {
                        logInfo(TAG, "No activity available for backup show, fallback failed")
                    }
                } else {
                    logInfo(TAG, "No backup available for fallback")
                }
            }
        }.launchIn(scope)
    }

    override suspend fun poll(): AuctionResult {
        logInfo(TAG, "poll(): suspending until ad available...")
        val result = slots.poll()
        logInfo(TAG, "poll(): got ${result.adSource.getStats().demandId.demandId} @ ${result.adSource.getStats().price}")

        // Observe for ShowFailed to enable automatic fallback to backup ad
        observeShowFallback(result)

        return result
    }

    override fun clear() {
        logInfo(TAG, "clear(): slots=${slots.description()}, loadingState=${loadingState.value}")
        retryAttempt = 0
        autoRestartJob?.cancel()
        autoRestartJob = null
        loadingJob?.cancel()
        loadingJob = null

        // Extract ads without destroying — preserve for next instance
        val preserved = slots.extractAll()
        if (persistedState.preservedAdsConsumed) {
            // Ads were already claimed by a new instance via pop() snapshot
            // extractAll cancelled our observe jobs — just drop the references
            logInfo(TAG, "clear(): ${preserved.size} ads already transferred to new instance, skipping preserve")
        } else {
            persistedState.preservedAds.clear()
            persistedState.preservedAds.addAll(preserved)
            persistedState.preservedByPop = false
            logInfo(TAG, "clear(): preserved ${preserved.size} ads for next instance")
        }
        persistedState.preservedAdsConsumed = false

        // Preserve remaining units for next instance
        persistedState.preservedRemainingUnits.clear()
        persistedState.preservedRemainingUnits.addAll(remainingUnits)
        persistedState.preservedRemainingRound = remainingRound
        logInfo(TAG, "clear(): preserved ${remainingUnits.size} remaining units for next instance")

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
        val validTokens = getValidRtbTokens()
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

            for (adUnit in currentRound.adUnits) {
                loadIndex++
                logInfo(TAG, "Load: [$loadIndex/${currentRound.adUnits.size}] processing ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}")

                if (slots.isFull()) {
                    logInfo(TAG, "Load: [$loadIndex] BREAK — both slots filled")
                    break
                }

                // preferRtb: skip CPM units to reach RTB faster
                if (preferRtb?.get() == true && adUnit.bidType == BidType.CPM) {
                    logInfo(TAG, "Load: [$loadIndex] SKIP CPM ${adUnit.demandId} (preferRtb mode)")
                    continue
                }

                // Skip units from networks already cached — avoids duplicate networks in slots
                if (adUnit.demandId in slots.cachedDemandIds) {
                    logInfo(TAG, "Load: [$loadIndex] SKIP — ${adUnit.demandId} already cached")
                    continue
                }
                val result = loader.loadUnit(adUnit, currentRound)
                if (result?.roundStatus == RoundStatus.Successful) {
                    val demandId = result.adSource.getStats().demandId.demandId
                    val price = result.adSource.getStats().price
                    logInfo(TAG, "Load: [$loadIndex] ✓ FILL from $demandId @ $price")
                    handleFill(result, currentRound, adTypeParam, onSuccess)
                    if (preferRtb?.get() == true) {
                        logInfo(TAG, "Load: preferRtb fill — abandoning waterfall")
                        break
                    }
                } else {
                    logInfo(TAG, "Load: [$loadIndex] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
                }
            }

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
        val demandId = result.adSource.getStats().demandId.demandId
        val price = result.adSource.getStats().price
        logInfo(TAG, "handleFill(): $demandId @ $price, slots before=${slots.description()}, callbackFired=${callbackFired.get()}")

        val primaryUpdated = slots.insert(result)
        logInfo(TAG, "handleFill(): $demandId → primaryUpdated=$primaryUpdated")
        slots.logCacheStatus("handleFill after insert")

        if (primaryUpdated && callbackFired.compareAndSet(false, true)) {
            logInfo(TAG, "handleFill(): FIRING onSuccess callback for $demandId @ $price")
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
        remainingUnits.forEachIndexed { index, unit ->
            logInfo(TAG, "  #${index + 1}  ${unit.demandId} / ${unit.bidType} / ${unit.pricefloor}")
        }

        // Refresh expired RTB tokens before walking
        val rtbDemandIds = remainingUnits
            .filter { it.bidType == BidType.RTB }
            .map { it.demandId }
            .toSet()

        val validTokens = getValidRtbTokens()
        val expiredRtbDemandIds = rtbDemandIds - validTokens.keys

        val tokens = if (expiredRtbDemandIds.isNotEmpty()) {
            logInfo(TAG, "walkRemainingUnits(): refreshing tokens for: $expiredRtbDemandIds")
            val freshTokens = loader.fetchTokens(adTypeParam)
                .filterKeys { it in expiredRtbDemandIds }
            val now = System.currentTimeMillis()
            freshTokens.forEach { (demandId, token) ->
                storedRtbTokens[demandId] = StoredToken(token, now)
            }
            logInfo(TAG, "walkRemainingUnits(): refreshed ${freshTokens.size} tokens")
            validTokens + freshTokens
        } else {
            validTokens
        }

        var index = 0
        val iterator = remainingUnits.iterator()
        while (iterator.hasNext()) {
            if (slots.isFull()) {
                logInfo(TAG, "Remaining: BREAK — both slots filled, ${remainingUnits.size - index} units kept for next round")
                break
            }
            val adUnit = iterator.next()
            iterator.remove()
            index++

            // Skip units from networks already cached — avoids duplicate networks in slots
            if (adUnit.demandId in slots.cachedDemandIds) {
                logInfo(TAG, "Remaining: [$index] SKIP — ${adUnit.demandId} already cached")
                continue
            }

            logInfo(TAG, "Remaining: [$index] processing ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}")

            val result = loader.loadUnit(adUnit, round, tokens)
            if (result?.roundStatus == RoundStatus.Successful) {
                val demandId = result.adSource.getStats().demandId.demandId
                val price = result.adSource.getStats().price
                logInfo(TAG, "Remaining: [$index] ✓ FILL from $demandId @ $price")
                handleFill(result, round, adTypeParam, onSuccess)
            } else {
                logInfo(TAG, "Remaining: [$index] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
            }
        }

        logInfo(TAG, "walkRemainingUnits(): done, ${remainingUnits.size} units still remaining for future rounds")
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
        val winnerDemandId = winner.adSource.getStats().demandId.demandId
        val winnerPrice = winner.adSource.getStats().price
        logInfo(TAG, "notifyWinner(): winner=$winnerDemandId @ $winnerPrice, externalWinNotifications=$externalWinNotificationsEnabled")

        winner.adSource.markWin()
        logInfo(TAG, "notifyWinner(): markWin() called on $winnerDemandId")

        if (!externalWinNotificationsEnabled) {
            if (winner !is AuctionResult.Bidding && winner.adSource is WinLossNotifiable) {
                (winner.adSource as WinLossNotifiable).notifyWin()
                logInfo(TAG, "notifyWinner(): notifyWin() sent to $winnerDemandId")
            } else {
                logInfo(TAG, "notifyWinner(): skipped notifyWin() (isBidding=${winner is AuctionResult.Bidding}, isWinLossNotifiable=${winner.adSource is WinLossNotifiable})")
            }
        }
    }

    // --- RTB Token Storage ---

    /**
     * Stores RTB tokens from the round with current timestamp.
     * Tokens expire after [RTB_TOKEN_EXPIRATION_MS] (15 minutes).
     */
    private fun storeRtbTokens(round: WaterfallLoader.AuctionRound) {
        val noBidDemandIds = round.response.noBids?.map { it.demandId }?.toSet() ?: emptySet()
        val rtbUnits = round.adUnits
            .filter { it.bidType == BidType.RTB }
            .filter { it.demandId !in noBidDemandIds }
        val now = System.currentTimeMillis()
        var storedCount = 0
        for (unit in rtbUnits) {
            val token = round.tokens[unit.demandId]
            if (token != null) {
                storedRtbTokens[unit.demandId] = StoredToken(token, now)
                storedCount++
                logInfo(TAG, "storeRtbTokens(): stored ${unit.demandId} token (expires in ${RTB_TOKEN_EXPIRATION_MS / 60_000}min)")
            }
        }
        logInfo(TAG, "storeRtbTokens(): stored $storedCount tokens, total=${storedRtbTokens.size}: [${storedRtbTokens.keys.joinToString()}]")
    }

    /**
     * Returns valid (non-expired) RTB tokens, removing expired ones.
     * Each network's expiration is tracked independently.
     */
    private fun getValidRtbTokens(): Map<String, TokenInfo> {
        val now = System.currentTimeMillis()
        val expired = storedRtbTokens.filter { (_, stored) -> stored.isExpired(now) }.keys
        if (expired.isNotEmpty()) {
            logInfo(TAG, "getValidRtbTokens(): removing ${expired.size} expired tokens: [${expired.joinToString()}]")
            expired.forEach { storedRtbTokens.remove(it) }
        }
        val valid = storedRtbTokens.mapValues { it.value.tokenInfo }
        logInfo(TAG, "getValidRtbTokens(): ${valid.size} valid tokens: [${valid.keys.joinToString()}]")
        return valid
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
private const val RTB_TOKEN_EXPIRATION_MS = 15 * 60 * 1000L // 15 minutes
