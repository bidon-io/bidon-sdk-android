package org.bidon.sdk.auction.impl

import android.os.SystemClock
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.logs.logging.impl.logInfo
import kotlin.random.Random

internal class PriceFloorStrategy {
    private val states = mutableMapOf<AdType, State>()

    fun calculate(
        adType: AdType,
        originalFloor: Double,
        recentFillRate: Double?,
        bidDistribution: Map<String, List<Double>>,
    ): Double {
        val state = states.getOrPut(adType) { State() }

        if (state.tripped) {
            if (SystemClock.elapsedRealtime() < state.cooldownUntil) {
                return originalFloor.also {
                    logInfo("PriceFloorStrategy", "Cooldown for floor: $it")
                }
            }
            state.tripped = false
        }

        val lastFillRate = state.lastFillRate
//        if (recentFillRate != null && lastFillRate != null && lastFillRate > 0) {
//            val drop = (lastFillRate - recentFillRate) / lastFillRate
//            if (drop > FILL_RATE_DROP_THRESHOLD) {
//                state.tripped = true
//                state.cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
//                state.lastFillRate = null
//                return originalFloor.also {
//                    logInfo("PriceFloorStrategy", "Floor tripped: $it")
//                }
//            }
//        }
        state.lastFillRate = recentFillRate

        val allBids = bidDistribution.values.flatten()
        if (allBids.size < MIN_SAMPLES) return originalFloor

        if (Random.nextDouble() < EXPLORATION_RATE) {
            return (originalFloor * Random.nextDouble(0.7, 1.3)).also {
                logInfo("PriceFloorStrategy", "Exploration floor: $it")
            }
        }

        return findMaxERFloor(allBids, originalFloor)
            .coerceIn(originalFloor * 0.5, originalFloor * 2.0)
            .also { logInfo("PriceFloorStrategy", "Calculated floor: $originalFloor -> $it") }
    }

    private fun findMaxERFloor(
        bids: List<Double>,
        fallback: Double,
    ): Double {
        if (bids.isEmpty()) return fallback

        var bestFloor = fallback
        var bestER = 0.0

        for (percentile in listOf(10, 25, 40, 50, 60, 75)) {
            val candidateFloor = bids.percentile(percentile)
            val fillRate = bids.count { it >= candidateFloor }.toDouble() / bids.size
            val avgEcpm = bids.filter { it >= candidateFloor }.average()
            val er = fillRate * avgEcpm

            if (er > bestER) {
                bestER = er
                bestFloor = candidateFloor
            }
        }

        return Random.nextDouble(bestFloor * 0.95, bestFloor * 1.2)
    }

    private fun List<Double>.percentile(p: Int): Double = if (isEmpty()) 0.0 else sorted()[(size - 1) * p / 100]

    private data class State(
        var tripped: Boolean = false,
        var cooldownUntil: Long = 0,
        var lastFillRate: Double? = null,
    )

    companion object {
        private const val MIN_SAMPLES = 5
        private const val EXPLORATION_RATE = 0.1
        private const val FILL_RATE_DROP_THRESHOLD = 0.20
        private const val COOLDOWN_MS = 15 * 60 * 1000L
    }
}
