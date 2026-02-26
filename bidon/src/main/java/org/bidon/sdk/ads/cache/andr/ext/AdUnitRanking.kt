package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

private const val PRIOR_STRENGTH = 2.0

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
    val statsWithData = stats.filter { it.sampleCount > 0 }
    val maxPrice =
        statsWithData
            .mapNotNull(DemandStatistics.Entry::avgBidPrice)
            .maxOrNull()
            ?.coerceAtLeast(0.01) ?: 1.0
    val minLatency =
        statsWithData.minOfOrNull(DemandStatistics.Entry::avgLatencyMs)?.coerceAtLeast(1.0) ?: 1.0
    val weights = RankingWeights(adType)
    return sortedByDescending {
        it.score(statsMap[it.demandId], maxPrice, minLatency, weights)
    }
}

private fun AdUnit.score(
    demandStats: DemandStatistics.Entry?,
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

    // Thompson Sampling: sample fill rate from Beta posterior
    val priorAlpha = weights.fillPrior * PRIOR_STRENGTH
    val priorBeta = (1.0 - weights.fillPrior) * PRIOR_STRENGTH
    val fills = demandStats?.fillCount ?: 0
    val nofills = (demandStats?.sampleCount ?: 0) - fills
    val sampledFill = sampleBeta(fills + priorAlpha, nofills + priorBeta)

    // Speed: deterministic (no TS — not a binary outcome)
    val speed =
        if (demandStats != null && demandStats.sampleCount > 0) {
            (minLatency / demandStats.avgLatencyMs).coerceAtLeast(SPEED_FLOOR)
        } else {
            COLD_START_SPEED_PRIOR
        }

    return sampledFill.pow(weights.alpha) * speed.pow(weights.beta) * normalizedEcpm.pow(weights.gamma)
}

private fun sampleBeta(
    alpha: Double,
    beta: Double
): Double {
    val x = sampleGamma(alpha)
    val y = sampleGamma(beta)
    return if (x + y > 0.0) x / (x + y) else 0.5
}

private fun sampleGamma(shape: Double): Double {
    if (shape < 1.0) {
        return sampleGamma(shape + 1.0) * Random.nextDouble().pow(1.0 / shape)
    }
    // Marsaglia and Tsang's method
    val d = shape - 1.0 / 3.0
    val c = 1.0 / sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = randomGaussian()
            v = 1.0 + c * x
        } while (v <= 0.0)
        v = v * v * v
        val u = Random.nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
        if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
    }
}

private fun randomGaussian(): Double {
    val u1 = Random.nextDouble()
    val u2 = Random.nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
