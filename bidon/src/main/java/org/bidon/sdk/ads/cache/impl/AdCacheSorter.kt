package org.bidon.sdk.ads.cache.impl

/**
 * Created by Bidon Team on 15/11/2024.
 *
 * Interface for sorting ad cache.
 */
internal fun interface AdCacheSorter {
    fun sort(collection: Collection<AdInstance>): Collection<AdInstance>

    companion object {
        val Timestamp: AdCacheSorter by lazy { AdCacheSorter { collection -> collection.sortedBy { it.timestamp } } }
        val MaxEcpm: AdCacheSorter by lazy { AdCacheSorter { collection -> collection.sortedByDescending { it.ecpm } } }
    }
}