package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.models.AdUnit
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

    private val preservedAds: MutableList<AuctionResult> = mutableListOf()
    private var preservedAdsConsumed: Boolean = false
    private var preservedByPop: Boolean = false
    private val preservedRemainingUnits: MutableList<AdUnit> = mutableListOf()
    private var preservedRemainingRound: WaterfallLoader.AuctionRound? = null

    /**
     * Restores preserved ads and remaining units into a fresh cache instance.
     * Called from init block of [AdCacheVladimirImpl].
     *
     * @return the preserved remaining round if remaining units were restored, null otherwise
     */
    fun restoreInto(
        slots: CacheSlotManager,
        remainingUnits: MutableList<AdUnit>,
    ): WaterfallLoader.AuctionRound? {
        // Restore preserved ads from previous instance (saved during clear() or pop() snapshot)
        val preserved = preservedAds.toList()
        if (preserved.isNotEmpty()) {
            preservedAds.clear()
            // Only mark consumed when ads came from pop() snapshot.
            // Ads from clear() preserve are NOT pop-snapshots — the new instance
            // must allow its own clear() to re-preserve them normally.
            if (preservedByPop) {
                preservedAdsConsumed = true
            }
            preservedByPop = false
            for (ad in preserved) {
                slots.insert(ad)
            }
            logInfo(TAG, "restoreInto: restored ${preserved.size} preserved ads → ${slots.description()}")
        }

        // Restore remaining units from previous instance
        val savedUnits = preservedRemainingUnits.toList()
        val savedRound = preservedRemainingRound
        var restoredRound: WaterfallLoader.AuctionRound? = null
        if (savedUnits.isNotEmpty() && savedRound != null) {
            remainingUnits.addAll(savedUnits)
            restoredRound = savedRound
            preservedRemainingUnits.clear()
            preservedRemainingRound = null
            logInfo(TAG, "restoreInto: restored ${savedUnits.size} remaining units from previous instance")
        }

        return restoredRound
    }

    /**
     * Preserves cache state when [AdCacheVladimirImpl.clear] is called.
     * Extracted ads and remaining units are saved for the next instance.
     */
    fun preserveOnClear(
        extractedAds: List<AuctionResult>,
        remainingUnits: List<AdUnit>,
        remainingRound: WaterfallLoader.AuctionRound?,
    ) {
        if (preservedAdsConsumed) {
            // Ads were already claimed by a new instance via pop() snapshot
            // extractAll cancelled our observe jobs — just drop the references
            logInfo(TAG, "preserveOnClear(): ${extractedAds.size} ads already transferred to new instance, skipping preserve")
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
        preservedRemainingRound = remainingRound
        logInfo(TAG, "preserveOnClear(): preserved ${remainingUnits.size} remaining units for next instance")
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
        logInfo(TAG, "snapshotOnPop(): snapshot ${remaining.size} remaining ads to persisted state")
    }
}

private const val TAG = "AdCacheVladimir.CachePersistedState"
