package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.denis.processors.AuctionParams
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
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
     * @param params Common auction parameters
     * @param auctionInfo Auction information for callback
     */
    suspend fun executeParallelAuction(
        rtbAdUnits: List<AdUnit>,
        cpmAdUnits: List<AdUnit>,
        params: AuctionParams,
        auctionInfo: AuctionInfo,
    ) {
        // Record cache state BEFORE auction
        val cacheWasEmpty = ReadyToShowCache.isEmpty()
        callbackCoordinator.setCacheEmptyAtStart(cacheWasEmpty)

        coroutineScope {
            // RTB branch (independent failure domain)
            val rtbDeferred = async {
                if (rtbAdUnits.isEmpty()) {
                    return@async null
                }
                // supervisorScope isolates RTB failures (doesn't cancel CPM)
                supervisorScope {
                    val result = rtbProcessor.loadBestPayload(
                        rtbAdUnits = rtbAdUnits,
                        params = params,
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
                    return@async null
                }
                // supervisorScope isolates CPM failures (doesn't cancel RTB)
                supervisorScope {
                    val result = cpmProcessor.loadWaterfall(
                        adUnits = cpmAdUnits,
                        params = params,
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
        if (rtbSuccess || cpmSuccess) {
            // Both pipelines done — pick best from cache
            val bestEntry = ReadyToShowCache.peekFirst()
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
                    "cache_size=${ReadyToShowCache.size()})"
            )
            callbackCoordinator.notifyFailure(auctionInfo, BidonError.NoFill(DemandId("auction")))
        }
    }
}

private const val TAG = "[DenisCache] Orchestrator"
