package org.bidon.sdk.ads.cache

import org.bidon.sdk.ads.cache.impl.AdInstance

/**
 * Created by Bidon Team on 31/10/2024.
 */
internal interface AdCacheResolver {
    suspend fun sortWinners(list: List<AdInstance>): List<AdInstance>
}
