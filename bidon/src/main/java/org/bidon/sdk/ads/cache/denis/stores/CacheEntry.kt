package org.bidon.sdk.ads.cache.denis.stores

/**
 * Generic cache entry wrapper with TTL expiration tracking.
 *
 * Stores cached item (LoadedAd or RtbPayload) along with metadata for expiration,
 * duplicate detection, and auction tracking.
 *
 * @param T Type of cached value (LoadedAd or RtbPayload)
 * @property value The cached item
 * @property ecpm eCPM for comparison in duplicate detection (higher eCPM wins)
 * @property expiresAt Timestamp when entry expires (from TtlConfig.expiresAt())
 * @property demandId Demand network identifier for lookup
 * @property auctionId Auction identifier for tracking (STAT-02)
 * @property uid Unique identifier from AdUnit for cache key uniqueness
 */
internal data class CacheEntry<T>(
    val value: T,
    val ecpm: Double,
    val expiresAt: Long,
    val demandId: String,
    val auctionId: String,
    val uid: String = "${auctionId}_${demandId}_${System.nanoTime()}"  // Default fallback
) {
    companion object {
        /**
         * Factory function to create cache entry with expiration timestamp.
         *
         * @param value The cached item
         * @param ecpm eCPM for comparison
         * @param demandId Demand network identifier
         * @param auctionId Auction identifier
         * @param uid Unique identifier from AdUnit (optional, auto-generated if not provided)
         * @return CacheEntry with expiresAt set to TtlConfig.expiresAt()
         */
        fun <T> create(
            value: T,
            ecpm: Double,
            demandId: String,
            auctionId: String,
            uid: String? = null
        ): CacheEntry<T> {
            return CacheEntry(
                value = value,
                ecpm = ecpm,
                expiresAt = TtlConfig.expiresAt(),
                demandId = demandId,
                auctionId = auctionId,
                uid = uid ?: "${auctionId}_${demandId}_${System.nanoTime()}"
            )
        }
    }
}

/**
 * Extension function to check if cache entry has expired.
 *
 * @return true if entry has expired based on TtlConfig
 */
internal fun CacheEntry<*>.isExpired(): Boolean = TtlConfig.isExpired(expiresAt)
