package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.ads.cache.AdCacheSorter

/**
 * Created by Bidon Team on 26/11/2024.
 */
internal val MaxEcpmAdCacheSorter: AdCacheSorter by lazy {
    PriceAdCacheSorter()
}

private class PriceAdCacheSorter : AdCacheSorter {
    override suspend fun sort(collection: Collection<AdInstance>): Collection<AdInstance> {
        return collection.sortedByDescending { it.ecpm }
    }
}
