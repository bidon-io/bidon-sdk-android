package org.bidon.sdk.ads.cache.twolevel.storage

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.twolevel.config.TwoLevelCacheConfig

/**
 * Static-singleton cache stores per [AdType], matching iOS Cacher.swift pattern.
 *
 * iOS equivalent:
 *   Cacher.Main.bannerStorage       = CacheStorage(...)
 *   Cacher.Main.interstitialStorage = CacheStorage(...)
 *   Cacher.Fallback.bannerStorage   = FallbackCacheStorage(...)
 *   Cacher.Fallback.interstitialStorage = FallbackCacheStorage(...)
 *
 * All manager instances for the same [AdType] share the same [CacheStorage] and
 * [FallbackCacheStorage] pair. The ManagerPool manages managers per auctionKey;
 * the stores are shared underneath — one pair per ad type for the lifetime of
 * the process.
 *
 * Thread safety: [getOrCreate] is not internally synchronized. Callers must hold
 * their own lock (ManagerPool holds a Mutex) before calling this function.
 */
internal object TwoLevelCacheStores {

    data class StorePair(
        val main: CacheStorage,
        val fallback: FallbackCacheStorage,
    )

    // Lazily initialized on first access per AdType.
    // Guarded by the caller (ManagerPool Mutex).
    private val stores = mutableMapOf<AdType, StorePair>()

    /**
     * Returns the [StorePair] for [adType], creating it if necessary using [config].
     *
     * If stores already exist for the ad type, [config] is ignored — stores are
     * singletons and are never re-created after first use.
     *
     * Must be called from within the ManagerPool's Mutex to ensure thread safety.
     */
    fun getOrCreate(
        adType: AdType,
        config: TwoLevelCacheConfig,
    ): StorePair {
        return stores.getOrPut(adType) {
            StorePair(
                main = CacheStorage(
                    capacity = config.mainCacheSize,
                    iterationThreshold = config.threshold,
                ),
                fallback = FallbackCacheStorage(
                    capacity = config.fallbackCacheSize,
                ),
            )
        }
    }
}
