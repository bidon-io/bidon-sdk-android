package org.bidon.sdk.ads.cache.twolevel

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Entry point for creating (two-level cache) [AdCache] instances.
 *
 * Returns a [TwoLevelAdManagerProxy] that lazily resolves the real [TwoLevelAdManager]
 * from [ManagerPool] on the first [AdCache.cache] call, once the auctionKey is
 * available from [org.bidon.sdk.auction.AdTypeParam].
 */
internal object AdCacheTwoLevelFactory {

    /**
     * Creates a Two-Level Cache AdCache wrapper for the given [demandAd].
     *
     * The actual [TwoLevelAdManager] is resolved lazily from [ManagerPool] on the first
     * [AdCache.cache] call. All other AdCache methods delegate to the resolved manager
     * once it is available.
     */
    fun create(
        demandAd: DemandAd,
    ): AdCache {
        logInfo(TAG, "[TwoLevelCache] creating Two-Level Cache AdCache for ${demandAd.adType}")
        return TwoLevelAdManagerProxy(demandAd = demandAd)
    }

    private const val TAG = "[TwoLevelCache]"
}
