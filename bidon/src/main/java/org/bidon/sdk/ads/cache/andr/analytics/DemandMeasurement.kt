package org.bidon.sdk.ads.cache.andr.analytics

import org.bidon.sdk.ads.AdType

internal data class DemandMeasurement(
    val demandId: String,
    val adType: AdType,
    val timestamp: Long,
    val bidPrice: Double?,
    val filled: Boolean,
    val latencyMs: Long
)