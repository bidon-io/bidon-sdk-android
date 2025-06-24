package org.bidon.sdk.ads.interstitial

import android.app.Activity
import org.bidon.sdk.ads.BidonAd
import org.bidon.sdk.databinders.extras.Extras
import org.bidon.sdk.stats.WinLossNotifier

/**
 * Created by Bidon Team on 06/02/2023.
 */
class InterstitialAd @JvmOverloads constructor(
    auctionKey: String? = null
) : Interstitial by InterstitialImpl(auctionKey = auctionKey)

internal interface Interstitial : BidonAd, Extras, WinLossNotifier {
    fun destroyAd()
    fun showAd(activity: Activity)
    fun setInterstitialListener(listener: InterstitialListener)
}
