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
 * V4 implementation of AdCache with two-slot caching, discovery + rebid loading,
 * background waterfall continuation, and show fallback.
 *
 * Discovery round (once per session): Auction at low pricefloor, walk full waterfall (RTB + CPM).
 *   Stores RTB tokens along the way. If a fill is found, triggers a Rebid round.
 * Rebid round (follows Discovery): New auction at fill price so RTB networks rebid competitively.
 * Refill (all subsequent loads): Single auction at last shown price.
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
            var discoveryCompleted: Boolean = false
            val rtbTokens: MutableMap<String, StoredToken> = mutableMapOf()
            var lastFillPrice: Double? = null
            var rtbRequestedPrice: Double? = null
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
    private var rtbRequestedPrice: Double
        get() = persistedState.rtbRequestedPrice ?: DEFAULT_RTB_PRICE
        set(value) { persistedState.rtbRequestedPrice = value }
    private var lastShownPrice: Double?
        get() = persistedState.lastFillPrice
        set(value) { persistedState.lastFillPrice = value }
    private var isDiscoveryNeeded = !persistedState.discoveryCompleted
    private val storedRtbTokens: MutableMap<String, StoredToken> get() = persistedState.rtbTokens

    // Remaining units from a previous waterfall (e.g., Discovery) to try after the current round.
    // Persists across rounds: untried units carry forward to Refill.
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
            "cache(): slots=${slots.description()}, isDiscoveryNeeded=$isDiscoveryNeeded, " +
                "loadingState=${loadingState.value}, lastShownPrice=$lastShownPrice, retryAttempt=$retryAttempt"
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
        logInfo(TAG, "cache(): state→LOADING, callbackFired=$hasAd, launching ${if (isDiscoveryNeeded) "discoveryLoad" else "refillLoad"}")

        loadingJob = scope.launch {
            runCatching {
                if (isDiscoveryNeeded) {
                    runDiscoveryLoad(adTypeParam, onSuccess, onFailure)
                } else {
                    runRefillLoad(adTypeParam, onSuccess, onFailure)
                }
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
            lastShownPrice = price
            logInfo(TAG, "pop(): popped ${result.adSource.getStats().demandId.demandId} @ $price, lastShownPrice=$price")

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

                    // Track lastShownPrice for backup ad
                    lastShownPrice = backup.adSource.getStats().price
                    logInfo(TAG, "Updated lastShownPrice=$lastShownPrice for backup $backupDemandId")

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
        lastShownPrice = result.adSource.getStats().price
        logInfo(TAG, "poll(): got ${result.adSource.getStats().demandId.demandId} @ $lastShownPrice")

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

    // --- Discovery + Rebid (runs once per session) ---

    private suspend fun runDiscoveryLoad(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        // Mark immediately — discovery never runs again, even on failure or instance recreation
        isDiscoveryNeeded = false
        persistedState.discoveryCompleted = true

        logInfo(TAG, "runDiscoveryLoad(): starting Discovery + Rebid flow")

        // 10s timer: fires onSuccess early if slot1 filled (before Rebid completes)
        var discoveryAuctionInfo: AuctionInfo? = null
        val discoveryTimeoutJob = scope.launch {
            logInfo(TAG, "runDiscoveryLoad(): ${DISCOVERY_TIMEOUT_MS}ms timeout job started")
            delay(DISCOVERY_TIMEOUT_MS)
            logInfo(TAG, "runDiscoveryLoad(): timeout fired! slot1=${slots.peek() != null}, callbackFired=${callbackFired.get()}")
            if (slots.peek() != null && callbackFired.compareAndSet(false, true)) {
                logInfo(TAG, "runDiscoveryLoad(): TIMEOUT — firing early onSuccess callback")
                val info = discoveryAuctionInfo
                if (info != null) {
                    adTypeParam.activity.runOnUiThread { onSuccess(slots.peek()!!, info) }
                } else {
                    logInfo(TAG, "runDiscoveryLoad(): TIMEOUT — discoveryAuctionInfo is null, cannot fire callback")
                }
            }
        }

        // ========== DISCOVERY ==========
        // Walk waterfall at low pricefloor until the FIRST fill, then stop immediately.
        // The first fill is the most expensive ad (waterfall is sorted top-to-bottom by price).
        logInfo(TAG, "═══ DISCOVERY START ═══ pricefloor=$DEFAULT_RTB_PRICE, timeout=${GLOBAL_TIMEOUT_MS}ms")

        var discoveryRound: WaterfallLoader.AuctionRound? = null
        var discoveryFillDemandId: String? = null
        var discoveryIndex = 0

        val discoveryTimedOut = withTimeoutOrNull(GLOBAL_TIMEOUT_MS) {
            discoveryRound = loader.startRound(adTypeParam, pricefloor = DEFAULT_RTB_PRICE)
            val currentRound = discoveryRound!!
            logInfo(TAG, "Discovery: received ${currentRound.adUnits.size} adUnits")
            logWaterfall("Discovery", currentRound.adUnits)

            for (adUnit in currentRound.adUnits) {
                discoveryIndex++
                logInfo(TAG, "Discovery: [$discoveryIndex/${currentRound.adUnits.size}] processing ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}")

                val result = loader.loadUnit(adUnit, currentRound)
                if (result?.roundStatus == RoundStatus.Successful) {
                    val demandId = result.adSource.getStats().demandId.demandId
                    val price = result.adSource.getStats().price
                    discoveryFillDemandId = demandId
                    logInfo(TAG, "Discovery: [$discoveryIndex] ✓ FILL from $demandId (${adUnit.bidType}) @ $price — stopping Discovery")
                    handleFill(result, currentRound, adTypeParam, onSuccess)
                    break
                } else {
                    logInfo(TAG, "Discovery: [$discoveryIndex] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
                }
            }

            // Save untried units from Discovery for use after Rebid and in Refill rounds
            if (discoveryFillDemandId != null) {
                val untried = currentRound.adUnits.drop(discoveryIndex)
                remainingUnits.clear()
                remainingUnits.addAll(untried)
                remainingRound = currentRound
                logInfo(TAG, "Discovery: saved ${untried.size} remaining units for later")
            }
        } == null

        if (discoveryTimedOut) {
            logInfo(TAG, "Discovery: TIMED OUT after ${GLOBAL_TIMEOUT_MS}ms (processed $discoveryIndex units)")
        }

        rtbRequestedPrice = slots.primaryPrice ?: DEFAULT_RTB_PRICE
        logInfo(
            TAG,
            "═══ DISCOVERY DONE ═══ rtbRequestedPrice=$rtbRequestedPrice, " +
                "discoveryFillDemandId=$discoveryFillDemandId"
        )
        slots.logCacheStatus("Discovery done")

        // Collect stats and store RTB tokens (outside timeout)
        if (discoveryRound != null) {
            storeRtbTokens(discoveryRound!!)

            logInfo(TAG, "Discovery: collecting stats...")
            val discoveryRoundStat = loader.collectStats(discoveryRound!!)
            discoveryAuctionInfo = buildAuctionInfo(discoveryRound!!.response, discoveryRoundStat)
            logInfo(TAG, "Discovery: stats collected, discoveryAuctionInfo built")
        } else {
            logInfo(TAG, "Discovery: no round available (startRound timed out), skipping stats")
        }

        // ========== REBID ==========
        // If Discovery had no fill, skip Rebid and finalize with failure.
        if (discoveryFillDemandId == null) {
            logInfo(TAG, "═══ REBID SKIPPED ═══ Discovery had no fill")
            discoveryTimeoutJob.cancel()
            if (discoveryRound != null) {
                finalizeLoad(discoveryRound!!, null, adTypeParam, onSuccess, onFailure)
            } else {
                loadingState.value = LoadingState.IDLE
                if (callbackFired.compareAndSet(false, true)) {
                    logInfo(TAG, "runDiscoveryLoad(): FIRING onFailure callback (no fill, no round)")
                    adTypeParam.activity.runOnUiThread { onFailure(null, BidonError.NoAuctionResults) }
                }
                scheduleAutoRestart(adTypeParam)
            }
            return
        }

        // New round at fill price. Exclude the filled network:
        // - Its token is NOT fetched and NOT sent to the server
        // - Its ad units are skipped in the waterfall walk
        val excludedNetworks = setOf(discoveryFillDemandId!!)
        logInfo(TAG, "═══ REBID START ═══ pricefloor=$rtbRequestedPrice, excludedNetworks=$excludedNetworks, timeout=${GLOBAL_TIMEOUT_MS}ms")

        var rebidRound: WaterfallLoader.AuctionRound? = null
        var rebidIndex = 0

        val rebidTimedOut = withTimeoutOrNull(GLOBAL_TIMEOUT_MS) {
            rebidRound = loader.startRound(
                adTypeParam = adTypeParam,
                pricefloor = rtbRequestedPrice,
                excludedDemandIds = excludedNetworks,
            )
            val currentRound = rebidRound!!
            logInfo(TAG, "Rebid: received ${currentRound.adUnits.size} adUnits")
            logWaterfall("Rebid", currentRound.adUnits)

            for (adUnit in currentRound.adUnits) {
                rebidIndex++
                logInfo(TAG, "Rebid: [$rebidIndex/${currentRound.adUnits.size}] processing ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}")

                if (slots.isFull()) {
                    logInfo(TAG, "Rebid: [$rebidIndex] BREAK — both slots filled")
                    break
                }

                // Skip ad units from the network that filled in Discovery
                if (adUnit.demandId in excludedNetworks) {
                    logInfo(TAG, "Rebid: [$rebidIndex] SKIP — ${adUnit.demandId} filled in Discovery")
                    continue
                }

                val result = loader.loadUnit(adUnit, currentRound)
                if (result?.roundStatus == RoundStatus.Successful) {
                    val demandId = result.adSource.getStats().demandId.demandId
                    val price = result.adSource.getStats().price
                    logInfo(TAG, "Rebid: [$rebidIndex] ✓ FILL from $demandId (${adUnit.bidType}) @ $price")
                    handleFill(result, currentRound, adTypeParam, onSuccess)
                } else {
                    logInfo(TAG, "Rebid: [$rebidIndex] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
                }
            }

            // Walk remaining units from Discovery to fill empty slots
            if (!slots.isFull() && remainingUnits.isNotEmpty()) {
                walkRemainingUnits(adTypeParam, onSuccess)
            }
        } == null

        if (rebidTimedOut) {
            logInfo(TAG, "Rebid: TIMED OUT after ${GLOBAL_TIMEOUT_MS}ms (processed $rebidIndex units)")
        }

        discoveryTimeoutJob.cancel()
        logInfo(TAG, "═══ REBID DONE ═══")
        slots.logCacheStatus("Rebid done")

        // Collect stats, store RTB tokens, and finalize (outside timeout)
        if (rebidRound != null) {
            storeRtbTokens(rebidRound!!)

            val rebidRoundStat = loader.collectStats(rebidRound!!)
            finalizeLoad(rebidRound!!, rebidRoundStat, adTypeParam, onSuccess, onFailure)
        } else if (discoveryRound != null) {
            logInfo(TAG, "Rebid: no round available, finalizing with Discovery round")
            finalizeLoad(discoveryRound!!, null, adTypeParam, onSuccess, onFailure)
        } else {
            logInfo(TAG, "runDiscoveryLoad(): no round available (both startRound timed out)")
            loadingState.value = LoadingState.IDLE
            if (callbackFired.compareAndSet(false, true)) {
                logInfo(TAG, "runDiscoveryLoad(): FIRING onFailure callback (startRound timeout)")
                adTypeParam.activity.runOnUiThread { onFailure(null, BidonError.NoAuctionResults) }
            }
            scheduleAutoRestart(adTypeParam)
        }
    }

    // --- Refill (all subsequent loads) ---

    private suspend fun runRefillLoad(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        rtbRequestedPrice = slots.primaryPrice ?: lastShownPrice ?: rtbRequestedPrice
        val collectPricefloor = if (slots.slotCount > 0) {
            maxOf(rtbRequestedPrice, slots.bestPrice)
        } else {
            rtbRequestedPrice
        }
        logInfo(
            TAG,
            "runRefillLoad(): lastShownPrice=$lastShownPrice, rtbRequestedPrice=$rtbRequestedPrice, " +
                "collectPricefloor=$collectPricefloor, slots=${slots.description()}, timeout=${GLOBAL_TIMEOUT_MS}ms"
        )

        var round: WaterfallLoader.AuctionRound? = null
        var refillIndex = 0

        // Get valid (non-expired) tokens - removes expired ones automatically
        val validTokens = getValidRtbTokens()
        logInfo(TAG, "runRefillLoad(): using ${validTokens.size} valid RTB tokens: [${validTokens.keys.joinToString()}]")

        val timedOut = withTimeoutOrNull(GLOBAL_TIMEOUT_MS) {
            round = loader.startRound(
                adTypeParam = adTypeParam,
                pricefloor = rtbRequestedPrice,
                collectPricefloor = collectPricefloor,
                existingTokens = validTokens,
                excludedDemandIds = slots.cachedDemandIds,
            )
            val currentRound = round!!
            logWaterfall("Refill", currentRound.adUnits)
            slots.logCacheStatus("Refill start")

            for (adUnit in currentRound.adUnits) {
                refillIndex++
                logInfo(TAG, "Refill: [$refillIndex/${currentRound.adUnits.size}] processing ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}")

                if (slots.isFull()) {
                    logInfo(TAG, "Refill: [$refillIndex] BREAK — both slots filled")
                    break
                }
                // Skip units from networks already cached — avoids duplicate networks in slots
                if (adUnit.demandId in slots.cachedDemandIds) {
                    logInfo(TAG, "Refill: [$refillIndex] SKIP — ${adUnit.demandId} already cached")
                    continue
                }
                val result = loader.loadUnit(adUnit, currentRound)
                if (result?.roundStatus == RoundStatus.Successful) {
                    val demandId = result.adSource.getStats().demandId.demandId
                    val price = result.adSource.getStats().price
                    logInfo(TAG, "Refill: [$refillIndex] ✓ FILL from $demandId @ $price")
                    handleFill(result, currentRound, adTypeParam, onSuccess)
                } else {
                    logInfo(TAG, "Refill: [$refillIndex] ✗ ${adUnit.demandId} → ${result?.roundStatus ?: "null"}")
                    // RTB unit failed - invalidate its token so next round fetches fresh one
                    if (adUnit.bidType == BidType.RTB) {
                        invalidateRtbToken(adUnit.demandId)
                    }
                }
            }

            // Save untried units from this Refill waterfall for future rounds.
            // These are cheaper units the server returned but we didn't walk
            // (because slots filled early). They carry forward as remainingUnits.
            if (refillIndex < currentRound.adUnits.size) {
                val untried = currentRound.adUnits.drop(refillIndex)
                logInfo(TAG, "Refill: saving ${untried.size} untried units for future rounds")
                remainingUnits.addAll(0, untried) // prepend — newer waterfall units are higher priority
                remainingRound = currentRound
            }

            // Walk remaining units from previous rounds to fill empty slots
            if (!slots.isFull() && remainingUnits.isNotEmpty()) {
                walkRemainingUnits(adTypeParam, onSuccess)
            }
        } == null

        if (timedOut) {
            logInfo(TAG, "runRefillLoad(): TIMED OUT after ${GLOBAL_TIMEOUT_MS}ms (processed $refillIndex units)")
        }

        logInfo(TAG, "runRefillLoad(): waterfall done, collecting stats...")
        if (round != null) {
            // Store RTB tokens for future Refill rounds
            storeRtbTokens(round!!)

            val roundStat = loader.collectStats(round!!)
            finalizeLoad(round!!, roundStat, adTypeParam, onSuccess, onFailure)
        } else {
            // Timed out before startRound completed — no round to finalize
            logInfo(TAG, "runRefillLoad(): no round available (startRound timed out)")
            loadingState.value = LoadingState.IDLE
            if (callbackFired.compareAndSet(false, true)) {
                logInfo(TAG, "runRefillLoad(): FIRING onFailure callback (startRound timeout)")
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

        if (primaryUpdated) {
            val info = buildAuctionInfo(round.response)
            val wasAlreadyFired = callbackFired.getAndSet(true)
            if (wasAlreadyFired) {
                logInfo(TAG, "handleFill(): HOT-SWAP — re-firing onSuccess for $demandId @ $price")
            } else {
                logInfo(TAG, "handleFill(): FIRING onSuccess callback for $demandId @ $price")
            }
            adTypeParam.activity.runOnUiThread { onSuccess(result, info) }
        } else if (primaryUpdated) {
            logInfo(TAG, "handleFill(): primary updated but callback already fired — next cache() will pick it up")
        }
    }

    // --- Remaining Units ---

    /**
     * Walks remaining units from a previous waterfall (e.g., Discovery) to fill empty slots.
     * Remaining units are cheaper ad units that were never tried because Discovery stopped on first fill.
     * Refreshes expired RTB tokens before loading.
     *
     * Called after Rebid and Refill waterfalls if slots are still not full.
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
                if (adUnit.bidType == BidType.RTB) {
                    invalidateRtbToken(adUnit.demandId)
                }
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
                logInfo(TAG, "finalizeLoad(): FIRING onFailure callback (no fills), resetting lastShownPrice")
                lastShownPrice = null
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
     * Tokens expire after [RTB_TOKEN_EXPIRATION_MS] (30 minutes).
     */
    private fun storeRtbTokens(round: WaterfallLoader.AuctionRound) {
        val rtbUnits = round.adUnits.filter { it.bidType == BidType.RTB }
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

    /**
     * Invalidates (removes) a stored RTB token when loading fails.
     * This forces a fresh token fetch on the next round.
     */
    private fun invalidateRtbToken(demandId: String) {
        if (storedRtbTokens.remove(demandId) != null) {
            logInfo(TAG, "invalidateRtbToken(): removed expired/failed token for $demandId")
        }
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
private const val DISCOVERY_TIMEOUT_MS = 10_000L
private const val GLOBAL_TIMEOUT_MS = 29_000L
private const val DEFAULT_RTB_PRICE = 0.01
private const val RTB_TOKEN_EXPIRATION_MS = 30 * 60 * 1000L // 30 minutes
