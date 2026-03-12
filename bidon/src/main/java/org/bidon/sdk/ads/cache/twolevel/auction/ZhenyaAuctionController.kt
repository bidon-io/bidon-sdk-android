package org.bidon.sdk.ads.cache.twolevel.auction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.twolevel.storage.CacheStorage
import org.bidon.sdk.ads.cache.twolevel.storage.FallbackCacheStorage
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import kotlin.coroutines.resume

/**
 * Wraps the existing [Auction] interface, adding per-fill [singleLoadCompletion] callback support.
 *
 * Mirrors iOS ZhenyaAuctionController behavior:
 * - iOS processes ad units one-by-one via OperationQueue (maxConcurrentOperationCount=1).
 * - On Android, [Auction.start] delivers all winners at once; this controller simulates
 *   the sequential-per-fill behavior by iterating [AuctionResolver.sortWinners] results
 *   sequentially and calling [singleLoadCompletion] for each item.
 * - First fill fires [onSuccess] on [Dispatchers.Main].
 * - On auction failure, checks Fallback cache for ads >= pricefloor before propagating.
 */
internal class ZhenyaAuctionController(
    private val mainCache: CacheStorage,
    private val fallbackCache: FallbackCacheStorage,
    private val resolver: AuctionResolver,
    private val adTypeLabel: String,
) {
    // One dedicated scope per controller instance; cancelled via cancel()
    internal val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Start auction. Mirrors iOS ZhenyaAuctionController.load(completion:).
     *
     * [singleLoadCompletion] fires for each winner in price-descending order,
     * simulating the iOS sequential OperationQueue waterfall. The [isFirstLoad]
     * flag is true only for the very first winner in the iteration.
     *
     * [onSuccess] fires on [Dispatchers.Main] with the first-fill winner.
     * [onFailure] fires on [Dispatchers.Main] if no fills and Fallback is empty/below floor.
     */
    fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        singleLoadCompletion: suspend (winner: AuctionResult, isFirstLoad: Boolean) -> Unit,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        val auction: Auction = get()
        logInfo(TAG, "[$adTypeLabel] [Auction] start pricefloor=${adTypeParam.pricefloor}")

        auction.start(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            onSuccess = { winners, auctionInfo ->
                scope.launch {
                    processWinners(
                        winners = winners,
                        auctionInfo = auctionInfo,
                        pricefloor = adTypeParam.pricefloor,
                        singleLoadCompletion = singleLoadCompletion,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                }
            },
            onFailure = { auctionInfo, cause ->
                scope.launch {
                    handleAuctionFailure(
                        auctionInfo = auctionInfo,
                        cause = cause,
                        pricefloor = adTypeParam.pricefloor,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                }
            },
        )
    }

    /**
     * Suspend variant of [start]. Suspends the caller until the auction callback fires
     * and all per-winner [singleLoadCompletion] calls complete.
     *
     * Used by [ZhenyaAdManager.cacheInternal] so that [auctionRunning] is reset in
     * the `finally` block only after the full auction cycle finishes.
     */
    internal suspend fun startSuspending(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        singleLoadCompletion: suspend (AuctionResult, Boolean) -> Unit,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val auction: Auction = get()
        logInfo(TAG, "[$adTypeLabel] [Auction] startSuspending pricefloor=${adTypeParam.pricefloor}")

        auction.start(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            onSuccess = { winners, auctionInfo ->
                scope.launch {
                    processWinners(
                        winners = winners,
                        auctionInfo = auctionInfo,
                        pricefloor = adTypeParam.pricefloor,
                        singleLoadCompletion = singleLoadCompletion,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                    cont.resume(Unit)
                }
            },
            onFailure = { auctionInfo, cause ->
                scope.launch {
                    handleAuctionFailure(
                        auctionInfo = auctionInfo,
                        cause = cause,
                        pricefloor = adTypeParam.pricefloor,
                        onSuccess = onSuccess,
                        onFailure = onFailure,
                    )
                    cont.resume(Unit)
                }
            },
        )
    }

    fun cancel() {
        scope.coroutineContext[Job]?.cancel()
        logInfo(TAG, "[$adTypeLabel] [Auction] cancelled")
    }

    // ---

    private suspend fun processWinners(
        winners: List<AuctionResult>,
        auctionInfo: AuctionInfo,
        pricefloor: Double,
        singleLoadCompletion: suspend (AuctionResult, Boolean) -> Unit,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        if (winners.isEmpty()) {
            handleAuctionFailure(auctionInfo, BidonError.NoAuctionResults, pricefloor, onSuccess, onFailure)
            return
        }

        // Sort descending by price — mirrors iOS sorting winners before sequential processing
        val sorted = resolver.sortWinners(winners)
        logInfo(TAG, "[$adTypeLabel] [Auction] ${sorted.size} winners, iterating sequentially")

        mainCache.beginIteration()

        // Sequential iteration simulating iOS OperationQueue maxConcurrentOperationCount=1
        var firstFillFired = false
        for ((index, winner) in sorted.withIndex()) {
            val isFirstLoad = !firstFillFired
            logInfo(
                TAG,
                "[$adTypeLabel] [Auction] processing winner[$index] isFirstLoad=$isFirstLoad" +
                    " price=${winner.adSource.getStats().price}",
            )

            // Delegate routing decision to the manager via singleLoadCompletion
            singleLoadCompletion(winner, isFirstLoad)

            if (isFirstLoad) {
                firstFillFired = true
                // Fire onSuccess on Main for the first fill — mirrors iOS main-thread dispatch
                withContext(Dispatchers.Main) {
                    onSuccess(winner, auctionInfo)
                }
            }
        }

        if (!firstFillFired) {
            // All winners were empty — treat as failure
            handleAuctionFailure(auctionInfo, BidonError.NoAuctionResults, pricefloor, onSuccess, onFailure)
        }
    }

    private suspend fun handleAuctionFailure(
        auctionInfo: AuctionInfo?,
        cause: Throwable,
        pricefloor: Double,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(TAG, "[$adTypeLabel] [Auction] failed ($cause) — checking Fallback")

        // iOS: check fallback for ad >= pricefloor before propagating failure
        val fallbackAd = fallbackCache.peek()
        if (fallbackAd != null && fallbackAd.adSource.getStats().price >= pricefloor) {
            val popped = fallbackCache.popFirst()
            if (popped != null) {
                val demandId = popped.adSource.getStats().demandId.demandId
                logInfo(TAG, "[$adTypeLabel] [Auction] serving from Fallback: $demandId")
                val info = auctionInfo ?: buildSyntheticAuctionInfo(popped)
                withContext(Dispatchers.Main) { onSuccess(popped, info) }
                return
            }
        }

        logInfo(TAG, "[$adTypeLabel] [Auction] Fallback empty/below floor — propagating failure")
        withContext(Dispatchers.Main) { onFailure(auctionInfo, cause) }
    }

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
            adUnits = null,
        )
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
