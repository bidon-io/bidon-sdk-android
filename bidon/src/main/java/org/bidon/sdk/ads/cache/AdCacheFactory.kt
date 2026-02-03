package org.bidon.sdk.ads.cache

import org.bidon.sdk.adapter.DemandAd

/**
 * Factory interface for creating version-specific AdCache instances.
 */
internal interface AdCacheFactory {
    fun create(demandAd: DemandAd): AdCache
}
