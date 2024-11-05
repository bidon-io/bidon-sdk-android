package org.bidon.sdk.cache

interface AdCacheSettingsProvider {

    val settings: AdCacheSettings

    /**
     * Applies the ad cache settings.
     */
    fun setAdCacheSettings(settings: AdCacheSettings)

    /**
     * Class representing ad cache settings.
     *
     * @property banner Settings for banner ads.
     * @property interstitial Settings for interstitial ads.
     * @property rewardedVideo Settings for rewarded video ads.
     */
    data class AdCacheSettings(
        val banner: AdSettings = DefaultAdSettings,
        val interstitial: AdSettings = DefaultAdSettings,
        val rewardedVideo: AdSettings = DefaultAdSettings
    )

    /**
     * Class representing the settings for a specific ad type.
     *
     * @property sortStrategy The strategy used for sorting ads.
     * @property cacheSize The size of the cache for auction keys.
     * @property retryDelayMs The delay before retrying to load an ad after a no-fill response.
     */
    data class AdSettings(
        val sortStrategy: SortStrategy,
        val cacheSize: Int,
        val retryDelayMs: Long
    )

    /**
     * Sealed class representing the sorting strategy for ads.
     */
    sealed class SortStrategy {
        /**
         * Sort by the eCPM of the ad.
         */
        object ECPM : SortStrategy()

        /**
         * Sort by the timestamp of loading the ad.
         */
        object TIMESTAMP : SortStrategy()
    }

    companion object {
        private val DEFAULT_SORT_STRATEGY = SortStrategy.TIMESTAMP
        private const val DEFAULT_CACHE_SIZE = 1
        private const val DEFAULT_RETRY_DELAY_MS = 10_000L

        val DefaultAdSettings
            get() = AdSettings(
                sortStrategy = DEFAULT_SORT_STRATEGY,
                cacheSize = DEFAULT_CACHE_SIZE,
                retryDelayMs = DEFAULT_RETRY_DELAY_MS
            )
    }
}
