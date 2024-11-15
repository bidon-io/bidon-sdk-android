package org.bidon.sdk.cache

import androidx.annotation.IntRange
import org.bidon.sdk.cache.AdCacheSettingsProvider.SortStrategy.TIMESTAMP

/**
 * Created by Bidon Team on 07/11/2024.
 *
 * Interface for providing ad cache settings.
 */
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
     * @property cacheSize The size of the cache for auction keys,
     * automatically constrained between 1 and 10.
     * @property retryDelayMs The delay before retrying to load an ad after a no-fill response,
     * automatically constrained between 2000 ms and 64000 ms.
     */
    data class AdSettings(
        val sortStrategy: SortStrategy,
        @IntRange(from = 1, to = 10) val cacheSize: Int,
        @IntRange(from = 2_000, to = 64_000) val retryDelayMs: Int
    )

    /**
     * Sealed class representing the sorting strategy for ads.
     */
    sealed class SortStrategy {
        /**
         * Sort by the eCPM of the ad.
         */
        object MAX_ECPM : SortStrategy()

        /**
         * Sort by the timestamp of loading the ad.
         */
        object TIMESTAMP : SortStrategy()
    }

    companion object {
        const val MIN_CACHE_SIZE: Int = 1
        const val MAX_CACHE_SIZE: Int = 10
        const val MIN_RETRY_DELAY_MS: Int = 2_000
        const val MAX_RETRY_DELAY_MS: Int = 64_000
        private val DEFAULT_SORT_STRATEGY: SortStrategy = TIMESTAMP

        val DefaultAdSettings
            get() = AdSettings(
                sortStrategy = DEFAULT_SORT_STRATEGY,
                cacheSize = MIN_CACHE_SIZE,
                retryDelayMs = MIN_RETRY_DELAY_MS
            )
    }
}
