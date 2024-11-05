package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.ads.cache.AdCacheResolver

/**
 * Created by Bidon Team on 31/10/2024.
 */
internal val MaxEcpmAdCacheResolver: AdCacheResolver by lazy {
    AdCacheResolver { winners -> winners.sortedByDescending { it.ecpm } }
}

internal val FIFOAdCacheResolver: AdCacheResolver by lazy {
    AdCacheResolver { winners -> winners.sortedBy { it.timestamp } }
}