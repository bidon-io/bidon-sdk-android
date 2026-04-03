package org.bidon.sdk.ads.cache.twolevel.auction

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Thin orchestrator for the Two-Level Cache sequential auction.
 *
 * Delegates all auction logic to [SequentialAuctionPipeline].
 * Routing decisions (main vs. fallback insert) and fallback delivery on no-fill are
 * handled by [org.bidon.sdk.ads.cache.twolevel.TwoLevelAdManager].
 */
internal class TwoLevelAuctionController(
    private val pipeline: SequentialAuctionPipeline,
    private val adTypeLabel: String,
    private val auctionKey: String = "",
) {
    /**
     * Start the sequential auction pipeline.
     *
     * Suspends until the pipeline has processed all ad units.
     *
     * [singleLoadCompletion] is called immediately for every ad unit that fills.
     * [shouldContinueAuction] is the pre-filter: returns false when no cache can accept the bid.
     * [onComplete] is called once after all units are processed.
     */
    internal suspend fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        singleLoadCompletion: suspend (AuctionResult, Boolean) -> Unit,
        shouldContinueAuction: (ecpm: Double) -> Boolean,
        onComplete: suspend (AuctionInfo?, BidonError?) -> Unit,
    ) {
        logInfo(TAG, "[$adTypeLabel/$auctionKey] start pricefloor=${adTypeParam.pricefloor}")

        pipeline.execute(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            singleLoadCompletion = singleLoadCompletion,
            shouldContinueAuction = shouldContinueAuction,
            onComplete = onComplete,
        )
    }

    companion object {
        private const val TAG = "[TwoLevelCache]"
    }
}
