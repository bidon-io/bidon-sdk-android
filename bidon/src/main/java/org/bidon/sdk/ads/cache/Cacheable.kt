package org.bidon.sdk.ads.cache

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal interface Cacheable {
    /**
     * Configures the cache.
     */
    fun withSettings(settings: Settings)

    /**
     * Settings for the cache.
     */
    data class Settings(
        val cacheCapacity: Int,
        val noFillRetryDelayMs: Long,
    )

    /**
     * Default settings for the cache.
     */
    companion object {
        private const val CACHE_CAPACITY = 2
        private const val NOFILL_RETRY_DELAY_MS = 10_000L

        val DefaultSettings
            get() = Settings(
                cacheCapacity = CACHE_CAPACITY,
                noFillRetryDelayMs = NOFILL_RETRY_DELAY_MS
            )
    }
}