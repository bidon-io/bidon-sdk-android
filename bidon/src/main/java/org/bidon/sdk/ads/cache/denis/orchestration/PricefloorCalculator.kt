package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Dynamic pricefloor calculator with safety margin.
 *
 * Calculates pricefloor once at auction start based on cached eCPM values
 * to protect cached ad value while allowing slightly better bids to compete.
 *
 * User decision: "Apply safety margin: pricefloor = 0.9 * max(...)"
 *
 * Safety margin (90%) prevents blocking all auctions with slightly lower bids,
 * enabling discovery of better ads while still protecting cached value.
 *
 * Calculation rules:
 * 1. Find max eCPM across both caches
 * 2. Apply 0.9 multiplier (10% margin)
 * 3. Take max of user pricefloor and cached pricefloor
 * 4. Return calculated value (used for entire auction, not recalculated)
 */
internal object PricefloorCalculator {
    private const val TAG = "PricefloorCalculator"

    /**
     * Safety margin multiplier (90% of cached eCPM).
     *
     * Allows bids within 10% of cached value to compete,
     * while still protecting against significantly worse bids.
     */
    private const val SAFETY_MARGIN = 0.9

    /**
     * Calculate dynamic pricefloor from cache state.
     *
     * Formula: max(userPricefloor, SAFETY_MARGIN * max(readyToShow, rtbPayload))
     *
     * Examples:
     * - User: $1.00, Cached: $5.00 → $4.50 (90% of $5.00)
     * - User: $10.00, Cached: $5.00 → $10.00 (user minimum respected)
     * - User: $1.00, Cached: $0.00 → $1.00 (no cache, use user pricefloor)
     *
     * @param userPricefloor Publisher-configured minimum eCPM
     * @param readyToShowMaxEcpm Maximum eCPM from READY_TO_SHOW cache
     * @param rtbPayloadMaxEcpm Maximum eCPM from RTB_PAYLOAD cache
     * @return Calculated pricefloor for auction request
     */
    fun calculateDynamicPricefloor(
        userPricefloor: Double,
        readyToShowMaxEcpm: Double,
        rtbPayloadMaxEcpm: Double
    ): Double {
        // Find highest eCPM across both caches
        val maxCachedEcpm = maxOf(readyToShowMaxEcpm, rtbPayloadMaxEcpm)

        // Apply safety margin (allow slightly better bids)
        val cachedFloorWithMargin = maxCachedEcpm * SAFETY_MARGIN

        // Respect user minimum
        val dynamicPricefloor = maxOf(userPricefloor, cachedFloorWithMargin)

        logInfo(
            TAG,
            "Dynamic pricefloor calculated: user=$userPricefloor, " +
                "readyToShow=$readyToShowMaxEcpm, rtbPayload=$rtbPayloadMaxEcpm, " +
                "maxCached=$maxCachedEcpm, result=$dynamicPricefloor"
        )

        return dynamicPricefloor
    }
}
