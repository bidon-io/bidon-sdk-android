package org.bidon.sdk.ads.cache.andr

import kotlinx.coroutines.CoroutineDispatcher
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import java.util.concurrent.ConcurrentHashMap

internal class RefillCoordinatorProvider {
    private val coordinators = ConcurrentHashMap<AdType, RefillCoordinator>()

    @Synchronized
    fun get(
        adType: AdType,
        tag: String,
        ioDispatcher: CoroutineDispatcher,
        store: AdStore<AuctionResultStore.Entry>,
        strategy: AdCacheStrategy,
    ): RefillCoordinator =
        coordinators.getOrPut(adType) {
            RefillCoordinator(tag, ioDispatcher, store, strategy)
        }
}
