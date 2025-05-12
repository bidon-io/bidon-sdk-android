package com.applovin.mediation.adapters.banner

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.applovin.mediation.MaxAdFormat
import com.applovin.mediation.adapters.keeper.AdInstance
import com.applovin.mediation.adapters.keeper.DEFAULT_DEMAND_ID
import com.applovin.mediation.adapters.keeper.DEFAULT_ECPM
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.ads.banner.BannerView

internal class BannerAdInstance(
    context: Context,
    format: MaxAdFormat,
    auctionKey: String? = null,
) : AdInstance {

    @VisibleForTesting
    internal val bannerAd = BannerView(context = context, auctionKey = auctionKey)

    private var bannerListener: BannerListener? = null
    private var bannerAdInfo: Ad? = null

    init {
        bannerAd.setBannerFormat(
            when (format) {
                MaxAdFormat.BANNER -> BannerFormat.Banner
                MaxAdFormat.MREC -> BannerFormat.MRec
                MaxAdFormat.LEADER -> BannerFormat.LeaderBoard
                else -> BannerFormat.Banner
            }
        )
    }

    override val ecpm: Double get() = bannerAdInfo?.price ?: DEFAULT_ECPM
    override val demandId: String get() = bannerAdInfo?.networkName ?: DEFAULT_DEMAND_ID
    override val isReady: Boolean get() = bannerAd.isReady()

    fun setListener(listener: BannerListener) {
        this.bannerListener = listener
        bannerAd.setBannerListener(listener)
    }

    fun addExtra(key: String, value: Any) {
        bannerAd.addExtra(key, value)
    }

    fun load(activity: Activity, pricefloor: Double) {
        bannerAd.loadAd(activity = activity, pricefloor = pricefloor)
    }

    fun show() {
        bannerAd.showAd()
    }

    override fun applyAdInfo(ad: Ad): BannerAdInstance = this.apply { bannerAdInfo = ad }

    override fun notifyLoss(winnerDemandId: String, winnerPrice: Double) {
        bannerAd.notifyLoss(
            winnerDemandId = "maxca_$winnerDemandId",
            winnerPrice = winnerPrice,
        )
    }

    override fun destroy() {
        bannerAd.destroyAd()
        bannerListener = null
        bannerAdInfo = null
    }
}
