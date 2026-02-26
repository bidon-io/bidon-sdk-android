package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

private const val MIN_SAMPLES = 10

private const val MIN_NETWORKS_FOR_NORMALIZATION = 3

private const val EXPLORATION_FACTOR = 0.1

private const val SPEED_FLOOR = 0.1

private const val COLD_START_SPEED_PRIOR = 0.5

private data class RankingWeights(
    val alpha: Double,
    val beta: Double,
    val gamma: Double,
    val fillPrior: Double,
)

private fun RankingWeights(adType: AdType): RankingWeights =
    when (adType) {
        AdType.Banner -> RankingWeights(alpha = 3.0, beta = 0.5, gamma = 0.5, fillPrior = 0.7)
        AdType.Interstitial -> RankingWeights(alpha = 2.0, beta = 2.0, gamma = 1.5, fillPrior = 0.6)
        AdType.Rewarded -> RankingWeights(alpha = 2.5, beta = 1.5, gamma = 1.0, fillPrior = 0.5)
    }

internal fun List<AdUnit>.sortedByRankDescending(
    stats: List<DemandStatistics.Entry>,
    adType: AdType,
): List<AdUnit> {
    val statsMap = stats.associateBy(DemandStatistics.Entry::demandId)
    val totalRequests = stats.sumOf(DemandStatistics.Entry::sampleCount).coerceAtLeast(1)
    val networksWithStats = stats.count { it.sampleCount >= MIN_SAMPLES }
    val statsWithData = stats.filter { it.sampleCount >= MIN_SAMPLES }
    val maxPrice =
        statsWithData
            .mapNotNull(DemandStatistics.Entry::avgBidPrice)
            .maxOrNull()
            ?.coerceAtLeast(0.01) ?: 1.0
    val minLatency =
        statsWithData.minOfOrNull(DemandStatistics.Entry::avgLatencyMs)?.coerceAtLeast(1.0) ?: 1.0
    val weights = RankingWeights(adType)
    return sortedByDescending {
        it.score(
            statsMap[it.demandId],
            totalRequests,
            networksWithStats,
            maxPrice,
            minLatency,
            weights
        )
    }
}

private fun AdUnit.score(
    demandStats: DemandStatistics.Entry?,
    totalRequests: Int,
    networksWithStats: Int,
    maxPrice: Double,
    minLatency: Double,
    weights: RankingWeights,
): Double {
    val price =
        when (bidType) {
            BidType.RTB -> pricefloor
            BidType.CPM -> demandStats?.avgBidPrice ?: pricefloor
        }
    val normalizedEcpm = (price / maxPrice).coerceAtMost(1.0)

    // Phase 1: per-network cold start
    if (demandStats == null || demandStats.sampleCount < MIN_SAMPLES) {
        return weights.fillPrior.pow(weights.alpha) * COLD_START_SPEED_PRIOR.pow(weights.beta) *
            normalizedEcpm.pow(
                weights.gamma
            ) * 1.1
    }

    val fillRate = demandStats.fillRate
    val speed = (minLatency / demandStats.avgLatencyMs).coerceAtLeast(SPEED_FLOOR)
    val baseScore =
        fillRate.pow(weights.alpha) * speed.pow(weights.beta) * normalizedEcpm.pow(weights.gamma)

    // Phase 2: global cold start — exploration as absolute addend
    if (networksWithStats < MIN_NETWORKS_FOR_NORMALIZATION) {
        val explorationBonus =
            EXPLORATION_FACTOR * sqrt(ln(totalRequests.toDouble()) / demandStats.sampleCount)
        return baseScore + explorationBonus
    }

    // Phase 3: full — exploration scaled proportionally
    val explorationBonus =
        EXPLORATION_FACTOR * sqrt(ln(totalRequests.toDouble()) / demandStats.sampleCount)
    return baseScore * (1 + explorationBonus)
}
