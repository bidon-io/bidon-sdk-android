package org.bidon.sdk.cache

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
     * @property cacheSize The size of the cache for auction keys,
     * automatically constrained between 1 and 10.
     * @property retryDelayMs The delay before retrying to load an ad after a no-fill response,
     * automatically constrained between 2000 ms and 64000 ms.
     * @property isCacheEnabled Whether the cache is enabled.
     */
    data class AdSettings(val cacheSize: Int, val retryDelayMs: Long, val isCacheEnabled: Boolean)

    companion object {
        const val MIN_CACHE_SIZE: Int = 1
        const val MAX_CACHE_SIZE: Int = 10
        const val MIN_RETRY_DELAY_MS: Long = 2_000
        const val MAX_RETRY_DELAY_MS: Long = 64_000
        const val CACHE_ENABLED: Boolean = true

        val DefaultAdSettings
            get() = AdSettings(
                cacheSize = MIN_CACHE_SIZE,
                retryDelayMs = MIN_RETRY_DELAY_MS,
                isCacheEnabled = CACHE_ENABLED
            )
    }
}
