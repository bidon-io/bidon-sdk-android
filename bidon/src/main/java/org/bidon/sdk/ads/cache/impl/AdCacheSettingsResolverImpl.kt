package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.AdCacheSettingsResolver
import org.bidon.sdk.ads.ext.asAdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.cache.AdCacheSettingsProvider.SortStrategy

internal class AdCacheSettingsResolverImpl(
    private val settingsProvider: AdCacheSettingsProvider
) : AdCacheSettingsResolver {

    override fun resolveAuctionKey(adTypeParam: AdTypeParam): Any {
        return adTypeParam.auctionKey ?: DEFAULT_AUCTION_KEY
    }

    override fun resolveSettings(adTypeParam: AdTypeParam): AdSettings {
        return when (adTypeParam.asAdType()) {
            AdType.Banner -> settingsProvider.settings.banner
            AdType.Interstitial -> settingsProvider.settings.interstitial
            AdType.Rewarded -> settingsProvider.settings.rewardedVideo
        }
    }

    override fun resolveSorter(adType: AdType): (Collection<AdInstance>) -> Collection<AdInstance> {
        val adSettings = when (adType) {
            AdType.Banner -> settingsProvider.settings.banner
            AdType.Interstitial -> settingsProvider.settings.interstitial
            AdType.Rewarded -> settingsProvider.settings.rewardedVideo
        }
        return when (adSettings.sortStrategy) {
            SortStrategy.ECPM -> { winners -> winners.sortedByDescending { it.ecpm } }
            SortStrategy.TIMESTAMP -> { winners -> winners.sortedBy { it.timestamp } }
        }
    }

    private companion object {
        private const val DEFAULT_AUCTION_KEY = "default"
    }
}
