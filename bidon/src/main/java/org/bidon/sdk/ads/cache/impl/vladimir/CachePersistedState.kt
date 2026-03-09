package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Manages cross-instance state preservation for ad caching.
 *
 * The host app creates a new cache instance on every show cycle (clear + new),
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

    /**
     * Restores preserved ads into a fresh cache instance.
     * Called from init block of [AdCacheVladimirImpl].
     */
    fun restoreInto(slots: CacheSlotManager) {
        val preserved = preservedAds.toList()
        if (preserved.isNotEmpty()) {
            preservedAds.clear()
            for (ad in preserved) {
                slots.insert(ad.result, ad.auctionInfo)
            }
            logInfo(TAG, "restoreInto: restored ${preserved.size} preserved ads → ${slots.description()}")
        }
    }

    /**
     * Preserves cache state when [AdCacheVladimirImpl.clear] is called.
     * Extracted ads are saved for the next instance.
     */
    fun preserveOnClear(extractedAds: List<CachedAd>) {
        preservedAds.clear()
        preservedAds.addAll(extractedAds)
        logInfo(TAG, "preserveOnClear(): preserved ${extractedAds.size} ads for next instance")
    }

    /**
     * Wipes all persisted state for this ad type.
     * Called during permanent teardown to ensure
     * no ad sources or metadata leak in the static state map.
     */
    fun wipe() {
        preservedAds.clear()
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
        logInfo(TAG, "snapshotOnPop(): snapshot ${remaining.size} remaining ads to persisted state")
    }
}

private const val TAG = "AdCacheVladimir.CachePersistedState"
