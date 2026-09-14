package org.bidon.sdk.ads.banner

import android.app.Activity
import android.content.Context
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.stats.WinLossNotifier

/**
 * Created by Bidon Team on 19/04/2023.
 */
internal interface BannerAd : WinLossNotifier, Extras {
    /**
     * Loaded Ad's size
     */
    val adSize: AdSize?

    fun setBannerFormat(bannerFormat: BannerFormat)
    fun loadAd(activity: Activity, pricefloor: Double = BidonSdk.DefaultPricefloor)

    /**
     * Loads an ad using any [Context]. The Activity required by ad networks is resolved from
     * [context] itself when it wraps an Activity, otherwise from the currently resumed Activity,
     * otherwise from the most recently resumed Activity that is still alive. Fails with
     * [org.bidon.sdk.config.BidonError.NoContextFound] when no Activity is available.
     */
    fun loadAd(context: Context, pricefloor: Double = BidonSdk.DefaultPricefloor)

    /**
     * Shows if banner is ready to show
     */
    fun isReady(): Boolean
    fun showAd()
    fun destroyAd()
    fun setBannerListener(listener: BannerListener?)
}
