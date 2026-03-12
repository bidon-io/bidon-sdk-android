package org.bidon.sdk.ads.cache.twolevel.auction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.twolevel.storage.FallbackCacheStorage
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Thin orchestrator for the V6 Two-Level Cache sequential auction.
 *
 * Delegates all auction logic to [SequentialAuctionPipeline], which directly replaces
 * the old [org.bidon.sdk.auction.Auction]-based approach.
 *
 * On auction failure the controller checks [fallbackCache] for an ad >= pricefloor.
 * If one is found it is popped and surfaced via [onComplete] as a success, mirroring the
 * iOS handlePerformAuctionRequestFailed behavior in ZhenyaFullscreenAdManager.
 *
 * Routing decisions (main vs. fallback insert) are NOT performed here — they belong in
 * [org.bidon.sdk.ads.cache.twolevel.ZhenyaAdManager] via the [singleLoadCompletion] lambda.
 */
internal class ZhenyaAuctionController(
    private val pipeline: SequentialAuctionPipeline,
    private val fallbackCache: FallbackCacheStorage,
    private val adTypeLabel: String,
) {
    // One dedicated scope per controller instance; cancelled via cancel().
    internal val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Start the sequential auction pipeline.
     *
     * Suspends until the pipeline has processed all ad units.
     *
     * [singleLoadCompletion] is called immediately for every ad unit that fills.
     * [onComplete] is called once after all units are processed:
     *   - On pipeline success  → (auctionInfo, null)
     *   - On pipeline failure  → fallback cache checked; if hit → (info, null); else → (null, error)
     */
    internal suspend fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        singleLoadCompletion: suspend (AuctionResult) -> Unit,
        onComplete: suspend (AuctionInfo?, BidonError?) -> Unit,
    ) {
        logInfo(TAG, "[$adTypeLabel] start pricefloor=${adTypeParam.pricefloor}")

        pipeline.execute(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            singleLoadCompletion = singleLoadCompletion,
            onComplete = { auctionInfo, error ->
                if (error != null) {
                    handlePipelineFailure(
                        auctionInfo = auctionInfo,
                        error = error,
                        pricefloor = adTypeParam.pricefloor,
                        onComplete = onComplete,
                    )
                } else {
                    onComplete(auctionInfo, null)
                }
            },
        )
    }

    fun cancel() {
        scope.coroutineContext[Job]?.cancel()
        logInfo(TAG, "[$adTypeLabel] cancelled")
    }

    // ---

    private suspend fun handlePipelineFailure(
        auctionInfo: AuctionInfo?,
        error: BidonError,
        pricefloor: Double,
        onComplete: suspend (AuctionInfo?, BidonError?) -> Unit,
    ) {
        logInfo(TAG, "[$adTypeLabel] pipeline failed ($error) — checking Fallback")

        // iOS: check fallback for ad >= pricefloor before propagating failure.
        // peek + pop is safe: FallbackCacheStorage uses Mutex internally,
        // and this controller runs one auction at a time (sequential pipeline).
        val fallbackAd = fallbackCache.peek()
        if (fallbackAd != null && fallbackAd.adSource.getStats().price >= pricefloor) {
            val popped = fallbackCache.popFirst()
            if (popped != null) {
                val demandId = popped.adSource.getStats().demandId.demandId
                logInfo(TAG, "[$adTypeLabel] serving from Fallback: $demandId")
                withContext(Dispatchers.Main) {
                    onComplete(auctionInfo, null)
                }
                return
            }
        }

        logInfo(TAG, "[$adTypeLabel] Fallback empty/below floor — propagating failure")
        withContext(Dispatchers.Main) {
            onComplete(null, error)
        }
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
