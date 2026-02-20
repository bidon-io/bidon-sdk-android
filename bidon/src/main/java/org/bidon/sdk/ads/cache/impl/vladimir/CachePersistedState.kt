package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Manages cross-instance state preservation for ad caching.
 *
 * Appodeal creates a new cache instance on every show cycle (clear + new),
 * so this state persists across instance recreations within the same process.
 * Keyed by [AdType] since each ad type has independent caching lifecycle.
 */
internal class CachePersistedState private constructor() {

    companion object {
        private val stateByAdType = mutableMapOf<AdType, CachePersistedState>()

        fun getState(adType: AdType): CachePersistedState =
            stateByAdType.getOrPut(adType) { CachePersistedState() }
    }

    var firstLoadCompleted: Boolean = false
    val rtbTokens: MutableMap<String, RtbTokenStore.StoredToken> = mutableMapOf()

    private val preservedAds: MutableList<CachedAd> = mutableListOf()
    private var preservedAdsConsumed: Boolean = false
    private var preservedByPop: Boolean = false
    private val preservedRemainingUnits: MutableList<RemainingUnit> = mutableListOf()

    // Generation counter to distinguish old vs new instance calls to preserveOnClear().
    // Incremented each time a new instance consumes preserved ads via restoreInto().
    // When preserveOnClear() is called with the same generation that consumed the ads,
    // it means the consuming instance itself is clearing — should preserve normally.
    private var generation: Long = 0
    private var consumedByGeneration: Long = -1

    // Tracks which AuctionResult references were in the last pop snapshot.
    // Used to detect orphaned ads: if a new instance was created before clear(),
    // any ad in extractAll() that wasn't in the snapshot is orphaned and must be destroyed.
    private val lastSnapshotRefs: MutableSet<AuctionResult> = mutableSetOf()

    /**
     * Restores preserved ads and remaining units into a fresh cache instance.
     * Called from init block of [AdCacheVladimirImpl].
     */
    fun restoreInto(
        slots: CacheSlotManager,
        remainingUnits: MutableList<RemainingUnit>,
    ): Long {
        val instanceGeneration = ++generation
        // Restore preserved ads from previous instance (saved during clear() or pop() snapshot)
        val preserved = preservedAds.toList()
        if (preserved.isNotEmpty()) {
            preservedAds.clear()
            // Only mark consumed when ads came from pop() snapshot.
            // Ads from clear() preserve are NOT pop-snapshots — the new instance
            // must allow its own clear() to re-preserve them normally.
            if (preservedByPop) {
                preservedAdsConsumed = true
                consumedByGeneration = instanceGeneration
            }
            preservedByPop = false
            for (ad in preserved) {
                slots.insert(ad.result, ad.auctionInfo)
            }
            logInfo(TAG, "restoreInto: restored ${preserved.size} preserved ads → ${slots.description()}")
        }

        // Restore remaining units from previous instance
        val savedUnits = preservedRemainingUnits.toList()
        if (savedUnits.isNotEmpty()) {
            remainingUnits.addAll(savedUnits)
            preservedRemainingUnits.clear()
            logInfo(TAG, "restoreInto: restored ${savedUnits.size} remaining units from previous instance")
        }
        return instanceGeneration
    }

    /**
     * Preserves cache state when [AdCacheVladimirImpl.clear] is called.
     * Extracted ads and remaining units are saved for the next instance.
     */
    fun preserveOnClear(
        extractedAds: List<CachedAd>,
        remainingUnits: List<RemainingUnit>,
        callerGeneration: Long,
    ) {
        if (preservedAdsConsumed && callerGeneration != consumedByGeneration) {
            // This clear() is from an OLD instance whose ads were already claimed
            // by a newer instance via pop() snapshot.
            // Any extracted ad that wasn't in the snapshot was loaded AFTER pop()
            // and never transferred — it's orphaned and must be destroyed.
            val orphaned = extractedAds.filter { it.result !in lastSnapshotRefs }
            if (orphaned.isNotEmpty()) {
                logInfo(TAG, "preserveOnClear(): destroying ${orphaned.size} orphaned ads (loaded after pop, not transferred)")
                orphaned.forEach { it.result.adSource.destroy() }
            }
            logInfo(TAG, "preserveOnClear(): ${extractedAds.size} ads already transferred to new instance, skipping preserve")
            lastSnapshotRefs.clear()
        } else {
            preservedAds.clear()
            preservedAds.addAll(extractedAds)
            preservedByPop = false
            logInfo(TAG, "preserveOnClear(): preserved ${extractedAds.size} ads for next instance")
        }
        preservedAdsConsumed = false

        // Preserve remaining units for next instance
        preservedRemainingUnits.clear()
        preservedRemainingUnits.addAll(remainingUnits)
        logInfo(TAG, "preserveOnClear(): preserved ${remainingUnits.size} remaining units for next instance")
    }

    /**
     * Wipes all persisted state for this ad type.
     * Called during permanent teardown ([AdCacheVladimirImpl.destroy]) to ensure
     * no ad sources or metadata leak in the static state map.
     */
    fun wipe() {
        preservedAds.clear()
        preservedAdsConsumed = false
        preservedByPop = false
        preservedRemainingUnits.clear()
        lastSnapshotRefs.clear()
        generation = 0
        consumedByGeneration = -1
        rtbTokens.clear()
        firstLoadCompleted = false
        logInfo(TAG, "wipe(): all persisted state cleared")
    }

    /**
     * Eagerly preserves remaining ads for next instance after pop().
     * Protects against new instance creation before clear() is called.
     */
    fun snapshotOnPop(slots: CacheSlotManager) {
        val remaining = slots.snapshotAll()
        preservedAds.clear()
        preservedAds.addAll(remaining)
        preservedAdsConsumed = false
        preservedByPop = true

        // Track which AuctionResult refs are in the snapshot so preserveOnClear
        // can detect orphaned ads (loaded after pop but before clear).
        lastSnapshotRefs.clear()
        lastSnapshotRefs.addAll(remaining.map { it.result })
        logInfo(TAG, "snapshotOnPop(): snapshot ${remaining.size} remaining ads to persisted state")
    }
}

private const val TAG = "AdCacheVladimir.CachePersistedState"
