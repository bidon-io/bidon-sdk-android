package org.bidon.sdk.ads.banner

import android.graphics.Point
import android.graphics.PointF

/**
 * Created by Aleksei Cherniaev on 04/09/2023.
 */
interface PositionedBanner : BannerAd {
    /**
     * Predefined [BannerPosition].
     * Always uses safe area insets.
     */
    fun setPosition(position: BannerPosition)

    /**
     * Offset presents top and left offset in pixels.
     * Anchor point presents pivot point in relative coordinates started from left/top corner.
     * @param offset in physical pixels
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