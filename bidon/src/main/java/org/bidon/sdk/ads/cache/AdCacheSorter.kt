package org.bidon.sdk.ads.cache

import org.bidon.sdk.ads.cache.impl.AdInstance

/**
 * Created by Bidon Team on 15/11/2024.
 *
 * Interface for sorting ad cache.
 */
internal fun interface AdCacheSorter {
    suspend fun sort(collection: Collection<AdInstance>): Collection<AdInstance>
}
