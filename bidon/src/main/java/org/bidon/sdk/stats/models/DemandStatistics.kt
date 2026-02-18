package org.bidon.sdk.stats.models

/**
 * Aggregated statistics for a demand network.
 *
 * @property demandId Unique identifier for the demand network
 * @property sampleCount Total number of recorded measurements
 * @property fillRate Percentage of requests that were filled (0.0 to 1.0)
 * @property avgBidPrice Average eCPM bid price in USD (null if no bids)
 * @property avgLatencyMs Average response latency in milliseconds
 * @property minBidPrice Minimum recorded bid price in USD (null if no bids)
 * @property maxBidPrice Maximum recorded bid price in USD (null if no bids)
 */
internal data class DemandStatistics(
    val demandId: String,
    val sampleCount: Int,
    val fillRate: Double,
    val avgBidPrice: Double?,
    val avgLatencyMs: Double,
    val minBidPrice: Double?,
    val maxBidPrice: Double?
)
