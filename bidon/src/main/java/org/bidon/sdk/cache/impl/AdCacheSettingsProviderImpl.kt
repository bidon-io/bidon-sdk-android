package org.bidon.sdk.cache.impl

import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdCacheSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MAX_CACHE_SIZE
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MAX_RETRY_DELAY_MS
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MIN_CACHE_SIZE
import org.bidon.sdk.cache.AdCacheSettingsProvider.Companion.MIN_RETRY_DELAY_MS

/**
 * Created by Bidon Team on 07/11/2024.
 *
 * Implementation of [AdCacheSettingsProvider].
 */
internal class AdCacheSettingsProviderImpl : AdCacheSettingsProvider {

    override var settings: AdCacheSettings = AdCacheSettings()

    override fun setAdCacheSettings(settings: AdCacheSettings) {
        this.settings = settings.copy(
            banner = settings.banner.validate(),
            interstitial = settings.interstitial.validate(),
            rewardedVideo = settings.rewardedVideo.validate()
        )
    }

    private fun AdSettings.validate(): AdSettings {
        return this.copy(
            cacheSize = this.cacheSize.coerceIn(MIN_CACHE_SIZE, MAX_CACHE_SIZE),
            retryDelayMs = this.retryDelayMs.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
        )
    }
}
