package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.ads.cache.AdCacheSorter

/**
 * Created by Bidon Team on 26/11/2024.
 *
 * Sorts ad cache by ecpm in descending order.
 */
internal class MaxEcpmAdCacheSorter : AdCacheSorter {
    override suspend fun sort(collection: Collection<AdInstance>): Collection<AdInstance> {
        return collection.sortedByDescending { it.ecpm }
    }
}
