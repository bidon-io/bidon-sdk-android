package org.bidon.sdk.ads.banner

import org.bidon.sdk.ads.BidonAd
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.stats.WinLossNotifier

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
internal interface BannerAd : BidonAd, WinLossNotifier, Extras {
    /**
     * Loaded Ad's size
     */
    val adSize: AdSize?

    fun setBannerFormat(bannerFormat: BannerFormat)
    fun showAd()
    fun destroyAd()
    fun setBannerListener(listener: BannerListener?)
}
