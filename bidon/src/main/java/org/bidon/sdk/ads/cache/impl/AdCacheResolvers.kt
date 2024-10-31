package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.ads.cache.AdCacheResolver

/**
 * Created by Bidon Team on 31/10/2024.
 */
internal val MaxEcpmAdCacheResolver: AdCacheResolver by lazy {
    PriceAdCacheResolver()
}

internal val FIFOAdCacheResolver: AdCacheResolver by lazy {
    TimestampAdCacheResolver()
}

private class PriceAdCacheResolver : AdCacheResolver {
    override suspend fun sortWinners(list: List<AdInstance>): List<AdInstance> {
        return list.sortedByDescending { it.ecpm } // Max ECPM
    }
}

private class TimestampAdCacheResolver : AdCacheResolver {
    override suspend fun sortWinners(list: List<AdInstance>): List<AdInstance> {
        return list.sortedBy { it.timestamp } // FIFO
    }
}