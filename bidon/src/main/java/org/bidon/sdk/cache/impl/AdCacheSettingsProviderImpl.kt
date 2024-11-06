package org.bidon.sdk.cache.impl

import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdCacheSettings

internal class AdCacheSettingsProviderImpl : AdCacheSettingsProvider {

    override var settings: AdCacheSettings = AdCacheSettings()

    override fun setAdCacheSettings(settings: AdCacheSettings) {
        this.settings = settings
    }
}
