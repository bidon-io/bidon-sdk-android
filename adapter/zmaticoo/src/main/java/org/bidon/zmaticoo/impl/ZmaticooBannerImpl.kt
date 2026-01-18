package org.bidon.zmaticoo.impl

import android.view.View
import com.maticoo.sdk.ad.banner.BannerAd
import com.maticoo.sdk.ad.banner.BannerAdListener
import com.maticoo.sdk.ad.banner.BannerAdOptions
import com.zmaticoo.sdk.base.common.MaticooIds
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

/**
 * Created by Vladimir Khrolovich on 12/01/2026.
 */
internal class ZmaticooBannerImpl :
    AdSource.Banner<ZmaticooBannerAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var bannerAd: BannerAd? = null
    private var bannerView: View? = null

    override var isAdReadyToShow: Boolean = false

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> =
        auctionParamsScope {
            ZmaticooBannerAuctionParams(
                activity = activity,
                bannerFormat = bannerFormat,
                adUnit = adUnit
            )
        }.onFailure {
            logError(TAG, "Failed to get auction param", it)
        }

    override fun load(adParams: ZmaticooBannerAuctionParams) {
        logInfo(TAG, "Starting with $adParams: $this")

        val placementId =
            adParams.placementId ?: return emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId,
                        "placementId is null"
                    )
                )
            )
        val payload =
            adParams.payload ?: return emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId,
                        "payload is null"
                    )
                )
            )

        val options = BannerAdOptions.Builder(placementId).build()
        val ad = BannerAd(adParams.activity, options)

        bannerAd = ad

        ad.setAdListener(
            object : BannerAdListener() {
                override fun onBannerAdReady(
                    adId: MaticooIds?,
                    view: View?
                ) {
                    logInfo(TAG, "onBannerAdReady")
                    bannerView = view
                    isAdReadyToShow = true
                    emitEvent(AdEvent.Fill(getAd() ?: return))
                }

                override fun onBannerAdFailed(
                    adId: MaticooIds?,
                    error: ComponentError?
                ) {
                    logInfo(TAG, "onBannerAdFailed: $error")
                    emitEvent(AdEvent.LoadFailed(BidonError.NoFill(demandId)))
                }

                override fun onBannerAdShow(adId: MaticooIds?) {
                    logInfo(TAG, "onBannerAdShow")
                    getAd()?.let {
                        emitEvent(AdEvent.Shown(it))
                        emitEvent(
                            AdEvent.PaidRevenue(
                                ad = it,
                                adValue =
                                AdValue(
                                    adRevenue = adParams.price / 1000.0,
                                    currency = AdValue.USD,
                                    precision = Precision.Precise
                                )
                            )
                        )
                    }
                }

                override fun onBannerAdShowFailed(
                    adId: MaticooIds?,
                    error: ComponentError?
                ) {
                    logInfo(TAG, "onBannerAdShowFailed: $error")
                    emitEvent(AdEvent.ShowFailed(BidonError.Unspecified(demandId)))
                }

                override fun onBannerAdClicked(adId: MaticooIds?) {
                    logInfo(TAG, "onBannerAdClicked")
                    emitEvent(AdEvent.Clicked(getAd() ?: return))
                }

                override fun onBannerAdClosed(adId: MaticooIds?) {
                    logInfo(TAG, "onBannerAdClosed")
                    emitEvent(AdEvent.Closed(getAd() ?: return))
                }

                override fun onBannerAdLeaveApp(adId: MaticooIds?) {
                    logInfo(TAG, "onBannerAdLeaveApp")
                }
            }
        )

        ad.loadAd(payload)
    }

    override fun getAdView(): AdViewHolder? = bannerView?.let { AdViewHolder(it) }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        bannerAd?.destroy()
        bannerAd = null
        bannerView = null
        isAdReadyToShow = false
    }
}

private const val TAG = "ZmaticooBannerImpl"
