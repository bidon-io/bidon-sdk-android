package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import java.util.concurrent.TimeUnit

internal data class AdCacheConfig(
    val auctionResultStoreCapacity: Int,
    val rtbResultsStoreTtl: Long,
    val rankingWeights: RankingWeights,
    val refillThreshold: Int,
) {
    data class RankingWeights(
        val alpha: Double,
        val beta: Double,
        val gamma: Double,
        val fillPrior: Double,
    )
}

internal val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(14)

internal class AdCacheConfigFactory {
    fun create(demandAd: DemandAd) =
        when (demandAd.adType) {
            AdType.Banner -> {
                AdCacheConfig(
                    auctionResultStoreCapacity = 4,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                        AdCacheConfig.RankingWeights(
                            alpha = 3.0,
                            beta = 0.5,
                            gamma = 0.5,
                            fillPrior = 0.7
                        ),
                    refillThreshold = 1,
                )
            }

            AdType.Interstitial -> {
                AdCacheConfig(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                        AdCacheConfig.RankingWeights(
                            alpha = 2.0,
                            beta = 2.0,
                            gamma = 1.5,
                            fillPrior = 0.6
                        ),
                    refillThreshold = -1,
                )
            }

            AdType.Rewarded -> {
                AdCacheConfig(
                    auctionResultStoreCapacity = 2,
                    rtbResultsStoreTtl = DEFAULT_TTL_MS,
                    rankingWeights =
                        AdCacheConfig.RankingWeights(
                            alpha = 2.5,
                            beta = 1.5,
                            gamma = 1.0,
                            fillPrior = 0.5
                        ),
                    refillThreshold = -1,
                )
            }
        }
}