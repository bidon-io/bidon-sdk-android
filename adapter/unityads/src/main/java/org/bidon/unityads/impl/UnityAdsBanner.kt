package org.bidon.unityads.impl

import android.view.View
import com.unity3d.ads.BannerAd
import com.unity3d.ads.BannerLoadConfiguration
import com.unity3d.ads.BannerShowListener
import com.unity3d.ads.BannerSize
import com.unity3d.ads.UnityAdsError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.banner.ext.height
import org.bidon.sdk.ads.banner.ext.width
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.unityads.ext.asBidonError

/**
 * Created by Bidon Team on 11/07/2023.
 */
internal class UnityAdsBanner :
    AdSource.Banner<UnityAdsBannerAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {
    private var bannerAd: BannerAd? = null
    private var bannerAdView: View? = null
    private var adUnit: AdUnit? = null

    override var isAdReadyToShow: Boolean = false

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            UnityAdsBannerAuctionParams(
                activity = activity,
                adUnit = adUnit,
                bannerFormat = bannerFormat,
            )
        }
    }

    override fun load(adParams: UnityAdsBannerAuctionParams) {
        logInfo(TAG, "Starting with $adParams")
        val placementId = adParams.placementId ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "placementId")
                )
            )
            return
        }
        adUnit = adParams.adUnit
        adParams.activity.runOnUiThread {
            val bannerSize = BannerSize(adParams.bannerFormat.width, adParams.bannerFormat.height)
            val loadConfig = BannerLoadConfiguration.Builder(placementId, bannerSize).build()

            BannerAd.load(loadConfig) { loadedBanner, error ->
                if (loadedBanner != null) {
                    logInfo(TAG, "onAdLoaded: $this")
                    bannerAd = loadedBanner
                    bannerAdView = loadedBanner.view
                    isAdReadyToShow = true

                    // Set show listener
                    loadedBanner.setShowListener(object : BannerShowListener {
                        override fun onBannerShown(bannerAd: BannerAd) {
                            logInfo(TAG, "onAdShown: $this")
                            getAd()?.let {
                                emitEvent(
                                    AdEvent.PaidRevenue(
                                        ad = it,
                                        adValue = AdValue(
                                            adRevenue = (adUnit?.pricefloor ?: 0.0) / 1000.0,
                                            currency = AdValue.USD,
                                            precision = Precision.Estimated
                                        )
                                    )
                                )
                            }
                        }

                        override fun onBannerClicked(bannerAd: BannerAd) {
                            logInfo(TAG, "onAdClicked: $this")
                            getAd()?.let {
                                emitEvent(AdEvent.Clicked(it))
                            }
                        }

                        override fun onBannerFailedToShow(bannerAd: BannerAd, error: UnityAdsError) {
                            logInfo(TAG, "Error while showing ad: ${error.message}. $this")
                        }
                    })

                    getAd()?.let {
                        emitEvent(AdEvent.Fill(it))
                    }
                } else {
                    logInfo(TAG, "Error while loading ad: ${error?.message}. $this")
                    isAdReadyToShow = false
                    emitEvent(AdEvent.LoadFailed(error.asBidonError()))
                }
            }
        }
    }

    override fun getAdView(): AdViewHolder? = bannerAdView?.let { AdViewHolder(it) }

    override fun destroy() {
        bannerAd?.destroy()
        bannerAd = null
        bannerAdView = null
    }
}

private const val TAG = "UnityAdsBanner"