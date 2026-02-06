package org.bidon.sdk.ads.cache.denis.processors

import org.bidon.sdk.auction.models.AdUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton for tracking CPM fill rate weights per demandId.
 *
 * Enables dynamic CPM waterfall optimization based on historical fill rates.
 * Ad networks with higher fill rates get priority over those that frequently fail.
 *
 * Weight scoring:
 * - Each demandId starts with weight = 10 (neutral)
 * - Successful fill: weight +1 (up to max 20)
 * - No fill: weight -1 (down to min 1)
 * - Final score: eCPM × (weight / 10.0)
 *
 * Weight factor interpretation:
 * - Weight 1 → 0.1x multiplier (heavily penalized, many no-fills)
 * - Weight 10 → 1.0x multiplier (neutral, default)
 * - Weight 20 → 2.0x multiplier (highly rewarded, many fills)
 *
 * Thread-safety: Uses ConcurrentHashMap + AtomicInteger for lock-free concurrent access.
 * Storage: In-memory only (resets on app restart).
 */
internal object WeightModel {
    private const val TAG = "[DenisCache] Weights"
    private const val DEFAULT_WEIGHT = 10
    private const val MIN_WEIGHT = 1
    private const val MAX_WEIGHT = 20

    /**
     * Thread-safe storage: demandId -> weight
     */
    private val weights = ConcurrentHashMap<String, Int>()

    /**
     * Record successful fill for demandId.
     *
     * Atomically increments weight (up to MAX_WEIGHT).
     *
     * @param demandId Demand network identifier
     */
    fun recordFill(demandId: String) {
        synchronized(weights) {
            val current = weights.getOrDefault(demandId, DEFAULT_WEIGHT)
            weights[demandId] = (current + 1).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        }
    }

    /**
     * Record no-fill (failure) for demandId.
     *
     * Atomically decrements weight (down to MIN_WEIGHT).
     *
     * @param demandId Demand network identifier
     */
    fun recordNoFill(demandId: String) {
        synchronized(weights) {
            val current = weights.getOrDefault(demandId, DEFAULT_WEIGHT)
            weights[demandId] = (current - 1).coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        }
    }

    /**
     * Get current weight for demandId.
     *
     * Returns DEFAULT_WEIGHT if demandId not found (never tracked).
     *
     * @param demandId Demand network identifier
     * @return Current weight (1-20)
     */
    fun getWeight(demandId: String): Int {
        return synchronized(weights) {
            weights.getOrDefault(demandId, DEFAULT_WEIGHT)
        }
    }

    /**
     * Calculate weighted score for ad unit.
     *
     * Formula: eCPM × (weight / 10.0)
     *
     * Weight normalization:
     * - Weight 1 → 0.1x multiplier
     * - Weight 10 → 1.0x multiplier (neutral)
     * - Weight 20 → 2.0x multiplier
     *
     * @param adUnit Ad unit to score
     * @return Weighted eCPM score
     */
    fun calculateScore(adUnit: AdUnit): Double {
        val weight = getWeight(adUnit.demandId)
        val weightFactor = weight / 10.0
        return adUnit.pricefloor * weightFactor
    }

    /**
     * Sort ad units by descending weighted score.
     *
     * Higher score = higher priority in waterfall.
     *
     * @param adUnits List of ad units to sort
     * @return Sorted list (highest score first)
     */
    fun sortByWeightedScore(adUnits: List<AdUnit>): List<AdUnit> {
        return adUnits.sortedByDescending { adUnit ->
            calculateScore(adUnit)
        }
    }

    /**
     * Clear all weights (for testing).
     */
    fun clear() {
        weights.clear()
    }
}
