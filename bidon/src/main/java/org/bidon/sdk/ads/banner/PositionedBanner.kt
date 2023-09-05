package org.bidon.sdk.ads.banner

import org.bidon.sdk.BidonSdk

/**
 * Created by Aleksei Cherniaev on 04/09/2023.
 */
interface PositionedBanner {
    fun setPosition(position: BannerPosition)
    fun setPosition(left: Int, top: Int)
    fun setRotation(degree: Int = 0)

    fun showAd(pricefloor: Double = BidonSdk.DefaultPricefloor)
    fun hideAd()

    fun setBannerListener(listener: BannerListener?)
}