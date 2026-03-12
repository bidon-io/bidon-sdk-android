package org.bidon.sdk.ads.cache.twolevel

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Entry point for creating V6 (two-level cache) [AdCache] instances.
 *
 * Returns a [ZhenyaAdManagerProxy] that lazily resolves the real [ZhenyaAdManager]
 * from [ManagerPool] on the first [AdCache.cache] call, once the auctionKey is
 * available from [org.bidon.sdk.auction.AdTypeParam].
 */
internal object AdCacheTwoLevelFactory {

    /**
     * Creates a V6 AdCache wrapper for the given [demandAd].
     *
     * The actual [ZhenyaAdManager] is resolved lazily from [ManagerPool] on the first
     * [AdCache.cache] call. All other AdCache methods delegate to the resolved manager
     * once it is available.
     */
    fun create(
        demandAd: DemandAd,
    ): AdCache {
        logInfo(TAG, "[TwoLevelCache] creating V6 AdCache for ${demandAd.adType}")
        return ZhenyaAdManagerProxy(demandAd = demandAd)
    }

    private const val TAG = "[TwoLevelCache]"
}
