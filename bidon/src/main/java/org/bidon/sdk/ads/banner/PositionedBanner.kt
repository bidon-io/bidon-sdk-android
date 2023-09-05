package org.bidon.sdk.ads.banner

/**
 * Created by Aleksei Cherniaev on 04/09/2023.
 */
interface PositionedBanner {
    fun setPosition(position: BannerPosition)
    fun setPosition(left: Int, top: Int)
    fun setRotation(degree: Int = 0)
    fun hideAd()
}