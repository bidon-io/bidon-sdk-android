package org.bidon.sdk.ads.cache

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.impl.AdInstance
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.cache.AdCacheSettingsProvider

/**
 * Created by Bidon Team on 06/11/2024.
 *
 * Interface for resolving ad cache settings.
 */
internal interface AdCacheSettingsResolver {
    /**
     * Resolves the auction key for the given ad type parameter.
     *
     * @param adTypeParam The ad type parameter.
     * @return The auction key.
     */
    fun resolveAuctionKey(adTypeParam: AdTypeParam): Any

    /**
     * Resolves the ad cache settings for the given ad type parameter.
     *
     * @param adTypeParam The ad type parameter.
     * @return The ad cache settings.
     */
    fun resolveSettings(adTypeParam: AdTypeParam): AdCacheSettingsProvider.AdSettings

    /**
     * Resolves the sorter for the given ad type parameter.
     *
     * @param adType The ad type.
     * @return The sorter.
     */
    fun resolveSorter(adType: AdType): (Collection<AdInstance>) -> Collection<AdInstance>
}
