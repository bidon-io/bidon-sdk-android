package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import java.util.concurrent.TimeUnit

internal data class AdCacheStrategy(
    val auctionResultStoreCapacity: Int,
    val rtbResultsStoreTtl: Long,
    val rankingWeights: RankingWeights,
    val refillThreshold: Int,
    val batchSize: Int,
) {
    data class RankingWeights(
        val alpha: Double,
        val beta: Double,
        val gamma: Double,
        val fillPrior: Double,
    )
}

internal val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(14)

internal class AdCacheStrategyFactory {
    fun create(demandAd: DemandAd) =
        when (demandAd.adType) {
            AdType.Banner -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 6,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                    AdCacheStrategy.RankingWeights(
                        alpha = 3.0,
                        beta = 0.5,
                        gamma = 0.5,
                        fillPrior = 0.7
                    ),
                    refillThreshold = 1,
                    batchSize = 3,
                )
            }

            AdType.Interstitial -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                    AdCacheStrategy.RankingWeights(
                        alpha = 2.0,
                        beta = 2.0,
                        gamma = 1.5,
                        fillPrior = 0.6
                    ),
                    refillThreshold = -1,
                    batchSize = 2,
                )
            }

            AdType.Rewarded -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                    AdCacheStrategy.RankingWeights(
                        alpha = 2.5,
                        beta = 1.5,
                        gamma = 1.0,
                        fillPrior = 0.5
                    ),
                    refillThreshold = -1,
                    batchSize = 2,
                )
            }
        }
}