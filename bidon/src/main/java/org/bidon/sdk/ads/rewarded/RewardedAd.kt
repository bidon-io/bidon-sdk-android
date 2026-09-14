package org.bidon.sdk.ads.rewarded

import android.app.Activity
import android.content.Context
import org.bidon.sdk.BidonSdk.DefaultPricefloor
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.stats.WinLossNotifier

/**
 * Created by Bidon Team on 06/02/2023.
 */
public class RewardedAd @JvmOverloads constructor(
    auctionKey: String? = null
) : Rewarded by RewardedImpl(auctionKey = auctionKey)

internal interface Rewarded : Extras, WinLossNotifier {
    fun isReady(): Boolean // for show
    fun loadAd(activity: Activity, pricefloor: Double = DefaultPricefloor)

    /**
     * Loads an ad using any [Context]; the Activity required by ad networks is resolved from
     * the currently resumed Activity. Fails with [org.bidon.sdk.config.BidonError.NoContextFound]
     * when no Activity is available.
     */
    fun loadAd(context: Context, pricefloor: Double = DefaultPricefloor)
    fun destroyAd()
    fun showAd(activity: Activity)
    fun setRewardedListener(listener: RewardedListener)
}
