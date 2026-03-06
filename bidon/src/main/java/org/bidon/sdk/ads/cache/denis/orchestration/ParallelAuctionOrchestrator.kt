package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import org.bidon.sdk.ads.cache.denis.processors.AuctionParams
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Parallel auction orchestrator for RTB + CPM execution.
 *
 * Executes RTB and CPM branches in parallel using async/supervisorScope:
 * - RTB failure doesn't cancel CPM
 * - CPM failure doesn't cancel RTB
 * - Both branches always run to completion
 * - Results cached in ReadyToShowCache; callbacks fired by caller after fill
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 */
internal class ParallelAuctionOrchestrator(
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor,
    private val readyToShowCache: ReadyToShowCache,
    adTypeLabel: String = "",
) {
    private val TAG = "[DenisCache] Orchestrator/$adTypeLabel"

    /**
     * Execute parallel auction (RTB + CPM).
     *
     * Process:
     * 1. Launch RTB and CPM branches in parallel (async + supervisorScope)
     * 2. Wait for both to complete
     * 3. Cache results sorted by eCPM
     *
     * Callbacks are NOT fired here — caller is responsible for building
     * AuctionInfo from RoundStat (after fill) and firing callbacks.
     *
     * @param rtbAdUnits RTB ad units from current auction response
     * @param cpmAdUnits CPM ad units to load
     * @param params Common auction parameters
     */
    suspend fun executeParallelAuction(
        rtbAdUnits: List<AdUnit>,
        cpmAdUnits: List<AdUnit>,
        params: AuctionParams,
    ) {
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
                    val cacheSize = readyToShowCache.size()
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
                    val cacheSize = readyToShowCache.size()
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
                    readyToShowCache.put(entry)
                }
                logInfo(
                    TAG,
                    "Cached ${sorted.size} ads from auction (sorted by eCPM): " +
                        sorted.joinToString { "${it.demandId}:$${"%.2f".format(it.ecpm)}" }
                )
            }
        }
    }
}
