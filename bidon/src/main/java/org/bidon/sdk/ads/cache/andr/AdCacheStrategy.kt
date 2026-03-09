package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import java.util.concurrent.TimeUnit

internal data class AdCacheStrategy(
    val auctionResultStoreCapacity: Int,
    val rtbResultsStoreTtl: Long,
    val explorationBudget: Int,
    val refillThreshold: Int,
    val batchSize: Int,
)

internal val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(14)

internal class AdCacheStrategyFactory {
    fun create(demandAd: DemandAd) =
        when (demandAd.adType) {
            AdType.Banner -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 6,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    explorationBudget = 2,
                    refillThreshold = 3,
                    batchSize = 3,
                )
            }

            AdType.Interstitial -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = 0,
                    explorationBudget = 1,
                    refillThreshold = 1,
                    batchSize = 2,
                )
            }

            AdType.Rewarded -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    explorationBudget = 1,
                    refillThreshold = 1,
                    batchSize = 2,
                )
            }
        }
}