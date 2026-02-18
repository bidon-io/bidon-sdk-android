package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.ads.cache.denis.stores.TtlConfig

/**
 * Immutable snapshot of cache state at auction start.
 *
 * Captures cache state ONCE at auction entry point to prevent race conditions
 * and inconsistent decisions during async processing. User decision: "Cache state
 * changes during processing are acceptable" - we use snapshot from auction start.
 *
 * Single snapshot pattern prevents:
 * - Inconsistent warm/cold start decisions
 * - Token skip list changes during collection
 *
 * @property readyToShowIsEmpty READY_TO_SHOW cache empty state
 * @property rtbPayloadIsEmpty RTB_PAYLOAD cache empty state
 * @property rtbPayloadMaxEcpm Maximum eCPM in RTB_PAYLOAD cache (or 0.0 if empty)
 * @property cachedDemandIds Set of demandIds with valid RTB payloads (for token skipping)
 * @property timestamp Snapshot creation time (monotonic, from TtlConfig)
 */
internal data class CacheStateSnapshot(
    val readyToShowIsEmpty: Boolean,
    val rtbPayloadIsEmpty: Boolean,
    val rtbPayloadMaxEcpm: Double,
    val cachedDemandIds: Set<String>,
    val timestamp: Long = TtlConfig.now()
) {
    companion object {
        /**
         * Capture current cache state atomically.
         *
         * Reads from both caches in sequence. Cache mutations during capture are
         * acceptable - we use a point-in-time snapshot for entire auction lifecycle.
         *
         * @return Immutable snapshot of cache state
         */
        fun capture(): CacheStateSnapshot = CacheStateSnapshot(
            readyToShowIsEmpty = ReadyToShowCache.isEmpty(),
            rtbPayloadIsEmpty = RtbPayloadCache.isEmpty(),
            rtbPayloadMaxEcpm = RtbPayloadCache.getMaxEcpm(),
            cachedDemandIds = RtbPayloadCache.getCachedDemandIds()
        )
    }
}
