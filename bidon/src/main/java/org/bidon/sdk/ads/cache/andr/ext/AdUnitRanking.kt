package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandStatistics
import kotlin.math.ln
import kotlin.math.sqrt

private const val MIN_SAMPLES = 10
private const val MIN_NETWORKS_FOR_NORMALIZATION = 3
private const val EXPLORATION_FACTOR = 0.1
private const val LATENCY_WEIGHT = 0.3

/**
 * Extension для сортировки AdUnit по UCB1 score.
 */
internal fun List<AdUnit>.sortedByRankDescending(
    stats: List<DemandStatistics>
): List<AdUnit> {
    val statsMap = stats.associateBy { it.demandId }
    val totalRequests = stats.sumOf { it.sampleCount }.coerceAtLeast(1)
    val networksWithStats = stats.count { it.sampleCount >= MIN_SAMPLES }
    val statsWithData = stats.filter { it.sampleCount >= MIN_SAMPLES }
    val maxPrice =
        statsWithData.mapNotNull { it.avgBidPrice }.maxOrNull()?.coerceAtLeast(0.01) ?: 1.0
    val maxLatency = statsWithData.maxOfOrNull { it.avgLatencyMs }?.coerceAtLeast(1.0) ?: 1000.0
    return sortedByDescending {
        it.score(
            statsMap[it.demandId],
            totalRequests,
            networksWithStats,
            maxPrice,
            maxLatency
        )
    }
}

private fun AdUnit.score(
    demandStats: DemandStatistics?,
    totalRequests: Int,
    networksWithStats: Int,
    maxPrice: Double,
    maxLatency: Double
): Double {
    val price =
        when (bidType) {
            BidType.RTB -> pricefloor
            BidType.CPM -> demandStats?.avgBidPrice ?: pricefloor
        }

    // Cold start для сети — exploration bonus
    if (demandStats == null || demandStats.sampleCount < MIN_SAMPLES) {
        return price * (1 + EXPLORATION_FACTOR)
    }

    // Cold start глобальный — raw score
    if (networksWithStats < MIN_NETWORKS_FOR_NORMALIZATION) {
        val expectedRevenue = price * demandStats.fillRate
        val explorationBonus =
            price * EXPLORATION_FACTOR * sqrt(ln(totalRequests.toDouble()) / demandStats.sampleCount)
        return expectedRevenue + explorationBonus
    }

    // Нормализованная формула
    val normalizedRevenue = (price * demandStats.fillRate) / maxPrice
    val normalizedLatency = demandStats.avgLatencyMs / maxLatency
    val explorationBonus =
        EXPLORATION_FACTOR * sqrt(ln(totalRequests.toDouble()) / demandStats.sampleCount)

    return normalizedRevenue * (1.0 - normalizedLatency * LATENCY_WEIGHT) + explorationBonus
}