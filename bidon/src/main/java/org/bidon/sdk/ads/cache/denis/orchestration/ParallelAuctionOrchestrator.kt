package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Parallel auction orchestrator for RTB + CPM execution.
 *
 * Executes RTB and CPM branches in parallel using async/supervisorScope:
 * - RTB failure doesn't cancel CPM
 * - CPM failure doesn't cancel RTB
 * - Both branches always run to completion
 * - Callback fires after both complete with best result from cache
 *
 * Cancellation support:
 * - cancelIfSameAuction() only cancels auctions with matching auctionId
 * - Prevents cancelling unrelated auctions when showing cached ad
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 */
internal class ParallelAuctionOrchestrator(
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor,
    private val callbackCoordinator: CallbackCoordinator,
) {
    /**
     * Execute parallel auction (RTB + CPM).
     *
     * Process:
     * 1. Record cache state before auction
     * 2. Launch RTB and CPM branches in parallel (async + supervisorScope)
     * 3. Wait for both to complete
     * 4. Fire callback based on results and cache state
     *
     * @param rtbAdUnits RTB ad units from current auction response
     * @param cpmAdUnits CPM ad units to load
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @param auctionInfo Auction information for callback
     */
    suspend fun executeParallelAuction(
        rtbAdUnits: List<AdUnit>,
        cpmAdUnits: List<AdUnit>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
        auctionInfo: AuctionInfo,
        resultsCollector: ResultsCollector,
    ) {
        // Record cache state BEFORE auction
        val cacheWasEmpty = ReadyToShowCache.isEmpty()
        callbackCoordinator.setCacheEmptyAtStart(cacheWasEmpty)

        logInfo(
            TAG,
            "Starting parallel auction: auctionId=$auctionId, " +
                "rtbAdUnits=${rtbAdUnits.size}, cpmAdUnits=${cpmAdUnits.size}, " +
                "cacheWasEmpty=$cacheWasEmpty"
        )

        coroutineScope {
            // RTB branch (independent failure domain)
            val rtbDeferred = async {
                if (rtbAdUnits.isEmpty()) {
                    logInfo(TAG, "Skipping RTB branch: no RTB ad units")
                    return@async null
                }
                // supervisorScope isolates RTB failures (doesn't cancel CPM)
                supervisorScope {
                    logInfo(TAG, "RTB branch starting (auctionId=$auctionId, adUnits=${rtbAdUnits.size})")
                    val result = rtbProcessor.loadBestPayload(
                        rtbAdUnits = rtbAdUnits,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = auctionConfigurationId,
                        auctionConfigurationUid = auctionConfigurationUid,
                        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                        pricefloor = pricefloor,
                        resultsCollector = resultsCollector,
                    )
                    val cacheSize = ReadyToShowCache.size()
                    logInfo(
                        TAG,
                        "RTB branch completed: success=${result.isSuccess}, " +
                            "cache_size=$cacheSize, error=${result.exceptionOrNull()?.message}"
                    )
                    result
                }
            }

            // CPM branch (independent failure domain)
            val cpmDeferred = async {
                if (cpmAdUnits.isEmpty()) {
                    logInfo(TAG, "Skipping CPM branch: no adUnits")
                    return@async null
                }
                // supervisorScope isolates CPM failures (doesn't cancel RTB)
                supervisorScope {
                    logInfo(TAG, "CPM branch starting (auctionId=$auctionId, adUnits=${cpmAdUnits.size})")
                    val result = cpmProcessor.loadWaterfall(
                        adUnits = cpmAdUnits,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = auctionConfigurationId,
                        auctionConfigurationUid = auctionConfigurationUid,
                        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                        pricefloor = pricefloor,
                        resultsCollector = resultsCollector,
                    )
                    val cacheSize = ReadyToShowCache.size()
                    logInfo(
                        TAG,
                        "CPM branch completed: success=${result.successCount}, " +
                            "failure=${result.failureCount}, cache_size=$cacheSize"
                    )
                    result
                }
            }

            // Wait for both branches to complete
            val rtbResult = rtbDeferred.await()
            val cpmResult = cpmDeferred.await()

            // Check if ANY success occurred
            val rtbSuccess = rtbResult?.isSuccess == true
            val cpmSuccess = cpmResult?.firstSuccess != null

            logInfo(
                TAG,
                "Parallel auction completed: rtbSuccess=$rtbSuccess, cpmSuccess=$cpmSuccess"
            )

            // Collect all cache entries from this auction, sort by eCPM desc, insert into cache
            val auctionEntries = mutableListOf<CacheEntry<AuctionResult>>()

            rtbResult?.getOrNull()?.let { (_, cacheEntry) ->
                auctionEntries.add(cacheEntry)
            }

            cpmResult?.cacheEntries?.let { entries ->
                auctionEntries.addAll(entries)
            }

            if (auctionEntries.isNotEmpty()) {
                val sorted = auctionEntries.sortedByDescending { it.ecpm }
                sorted.forEach { entry ->
                    ReadyToShowCache.put(entry)
                }
                logInfo(
                    TAG,
                    "Cached ${sorted.size} ads from auction (sorted by eCPM): " +
                        sorted.joinToString { "${it.demandId}:$${"%.2f".format(it.ecpm)}" }
                )
            }

            // Check cache state and notify appropriately
            checkAndNotifyCallback(
                rtbSuccess = rtbSuccess,
                cpmSuccess = cpmSuccess,
                rtbResult = rtbResult,
                cpmResult = cpmResult,
                auctionInfo = auctionInfo,
                cacheWasEmpty = cacheWasEmpty
            )
        }
    }

    /**
     * Check results after both pipelines complete and fire appropriate callback.
     *
     * Logic:
     * - If any success: pick best from ReadyToShowCache, fire onAdLoaded ONCE
     * - If both failed: fire onAdLoadFailed (only if cache was empty)
     */
    private fun checkAndNotifyCallback(
        rtbSuccess: Boolean,
        cpmSuccess: Boolean,
        rtbResult: Result<Pair<AuctionResult, CacheEntry<AuctionResult>>>?,
        cpmResult: org.bidon.sdk.ads.cache.denis.processors.CpmWaterfallResult?,
        auctionInfo: AuctionInfo,
        cacheWasEmpty: Boolean
    ) {
        // Get current cache state for detailed logging
        val cacheSize = ReadyToShowCache.size()
        val cacheIsEmpty = ReadyToShowCache.isEmpty()

        logInfo(
            TAG,
            "Cache observation: was_empty=$cacheWasEmpty, current_size=$cacheSize, " +
                "current_empty=$cacheIsEmpty, rtb_success=$rtbSuccess, cpm_success=$cpmSuccess"
        )

        if (rtbSuccess || cpmSuccess) {
            // Both pipelines done — pick best from cache
            val bestEntry = ReadyToShowCache.getBest()
            if (bestEntry != null) {
                logInfo(
                    TAG,
                    "Both pipelines complete: best ad=${bestEntry.demandId} " +
                        "ecpm=${"$%.2f".format(bestEntry.ecpm)}"
                )
                callbackCoordinator.notifySuccess(bestEntry.value, auctionInfo)
            } else {
                // Unexpected: success reported but cache is empty
                logInfo(
                    TAG,
                    "Warning: branch success but cache still empty " +
                        "(rtb=$rtbSuccess, cpm=$cpmSuccess)"
                )
            }
        } else {
            // Both branches failed
            logInfo(
                TAG,
                "Both RTB and CPM branches failed (cache_was_empty=$cacheWasEmpty, " +
                    "cache_size=$cacheSize)"
            )
            callbackCoordinator.notifyFailure(auctionInfo, BidonError.NoFill(DemandId("auction")))
        }
    }
}

private const val TAG = "[DenisCache] Orchestrator"
