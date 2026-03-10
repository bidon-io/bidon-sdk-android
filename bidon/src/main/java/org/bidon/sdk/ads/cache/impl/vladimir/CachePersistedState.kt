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
     *
     * Ads from adapters known to leak Activity references through third-party singletons
     * are destroyed instead of preserved. DTExchange's Fyber SDK retains the RequestListener
     * (with a captured Activity) in InneractiveAdSpotManager's static ConcurrentHashMap,
     * which prevents the Activity from being GC'd even after onDestroy().
     */
    fun preserveOnClear(extractedAds: List<CachedAd>) {
        preservedAds.clear()
        val (leaky, safe) = extractedAds.partition { it.result.demandId in LEAK_PRONE_DEMAND_IDS }
        for (ad in leaky) {
            logInfo(TAG, "preserveOnClear(): destroying leak-prone ad ${ad.result.demandId} @ ${ad.result.price}")
            ad.result.adSource.destroy()
        }
        preservedAds.addAll(safe)
        logInfo(TAG, "preserveOnClear(): preserved ${safe.size} ads, destroyed ${leaky.size} leak-prone ads")
    }

    /**
     * Eagerly preserves remaining ads for next instance after pop().
     * Protects against new instance creation before clear() is called.
     *
     * Note: unlike [preserveOnClear], this only snapshots — it does NOT destroy
     * leak-prone ads because they're still live in the slot manager.
     * Destruction happens later in [preserveOnClear] when the cache is cleared.
     */
    fun snapshotOnPop(slots: CacheSlotManager) {
        val remaining = slots.snapshotAll()
        preservedAds.clear()
        preservedAds.addAll(remaining.filter { it.result.demandId !in LEAK_PRONE_DEMAND_IDS })
        logInfo(TAG, "snapshotOnPop(): snapshot ${remaining.size} remaining ads, " +
            "excluded ${remaining.size - preservedAds.size} leak-prone ads from persisted state")
    }
}

private const val TAG = "AdCacheVladimir.CachePersistedState"

/**
 * Adapters whose third-party SDKs retain Activity references through process-lifetime singletons.
 * These ads must not be preserved across cache instances to avoid leaking destroyed Activities.
 *
 * DTExchange (Fyber): InneractiveAdSpotManager singleton keeps RequestListener in a ConcurrentHashMap,
 * which holds a closure over DTExchangeBannerAuctionParams.activity.
 */
private val LEAK_PRONE_DEMAND_IDS = setOf("dtexchange")
