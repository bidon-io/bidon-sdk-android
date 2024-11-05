package org.bidon.sdk.cache.impl

import org.bidon.sdk.cache.AdCacheSettingsProvider

internal class AdCacheSettingsProviderImpl : AdCacheSettingsProvider {

    override var settings: AdCacheSettingsProvider.AdCacheSettings =
        AdCacheSettingsProvider.AdCacheSettings()

    override fun setAdCacheSettings(settings: AdCacheSettingsProvider.AdCacheSettings) {
        this.settings = settings
    }
}
