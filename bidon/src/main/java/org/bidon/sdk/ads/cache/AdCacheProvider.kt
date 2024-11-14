package org.bidon.sdk.ads.cache

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.banner.BannerFormat

/**
 * Created by Bidon Team on 14/11/2024.
 *
 * Interface for providing ad cache.
 */
internal interface AdCacheProvider {
    /**
     * Provides the ad cache.
     *
     * @return The ad cache.
     */
    fun provide(demandAd: DemandAd, bannerFormat: BannerFormat? = null): AdCache
}
