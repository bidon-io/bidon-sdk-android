package com.applovin.mediation.adapters.interstitial

import android.app.Activity
import androidx.annotation.VisibleForTesting
import com.applovin.mediation.adapters.keeper.AdInstance
import com.applovin.mediation.adapters.keeper.DEFAULT_DEMAND_ID
import com.applovin.mediation.adapters.keeper.DEFAULT_ECPM
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.interstitial.InterstitialAd
import org.bidon.sdk.ads.interstitial.InterstitialListener

internal class InterstitialAdInstance(auctionKey: String? = null) : AdInstance {

    @VisibleForTesting
    internal val interstitialAd = InterstitialAd(auctionKey = auctionKey)

    private var interstitialListener: InterstitialListener? = null
    private var interstitialAdInfo: Ad? = null

    override val ecpm: Double get() = interstitialAdInfo?.price ?: DEFAULT_ECPM
    override val demandId: String get() = interstitialAdInfo?.networkName ?: DEFAULT_DEMAND_ID
    override val isReady: Boolean get() = interstitialAd.isReady()

    fun setListener(listener: InterstitialListener) {
        this.interstitialListener = listener
        interstitialAd.setInterstitialListener(listener)
    }

    fun addExtra(key: String, value: Any) {
        interstitialAd.addExtra(key, value)
    }

    fun load(activity: Activity, pricefloor: Double) {
        interstitialAd.loadAd(activity = activity, pricefloor = pricefloor)
    }

    fun show(activity: Activity) {
        interstitialAd.showAd(activity)
    }

    override fun applyAdInfo(ad: Ad): InterstitialAdInstance = this.apply { interstitialAdInfo = ad }

    override fun notifyLoss(winnerDemandId: String, winnerPrice: Double) {
        interstitialAd.notifyLoss(
            winnerDemandId = "maxca_$winnerDemandId",
            winnerPrice = winnerPrice,
        )
    }

    override fun destroy() {
        interstitialAd.destroyAd()
        interstitialListener = null
        interstitialAdInfo = null
    }
}
