package org.bidon.sdk.ads.banner

import android.graphics.Point
import android.graphics.PointF

/**
 * Created by Aleksei Cherniaev on 04/09/2023.
 */
interface PositionedBanner : BannerAd {
    fun setPosition(position: BannerPosition)

    /**
     * Offset presents top and left offset in pixels.
     * Pivot presents pivot/anchor point in relative coordinates started from left/top corner.
     * @param rotation in degrees
     * @param anchor min value is 0f, max value is 1f
     */
    fun setCustomPosition(
        offset: Point,
        rotation: Int,
        anchor: PointF
    )

    fun hideAd()
}