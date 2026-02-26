package org.bidon.sdk.ads.cache.andr.analytics

import org.bidon.sdk.ads.AdType

/**
 * Individual measurement record for demand network performance.
 *
 * @property demandId Unique identifier for the demand network
 * @property adType Type of ad (banner, interstitial, rewarded)
 * @property timestamp Unix timestamp in milliseconds when measurement was recorded
 * @property bidPrice eCPM bid price in USD, null if no bid
 * @property filled Whether the ad request was filled
 * @property latencyMs Time in milliseconds from request to response
 */
internal data class DemandMeasurement(
    val demandId: String,
    val adType: AdType,
    val timestamp: Long,
    val bidPrice: Double?,
    val filled: Boolean,
    val latencyMs: Long
)