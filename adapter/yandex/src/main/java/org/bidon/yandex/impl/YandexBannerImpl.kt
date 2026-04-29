package org.bidon.yandex.impl

import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.stats.models.BidType
import org.bidon.yandex.ext.asBidonAdValue
import org.bidon.yandex.ext.toYandexBannerSize

/**
 * Created by Bidon Team on 07/08/2024.
 */
internal class YandexBannerImpl :
    AdSource.Banner<YandexBannerAuctionParam>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl(),
    YandexLoader by singleLoader {

    private var bannerView: BannerAdView? = null

    override val isAdReadyToShow: Boolean
        get() = bannerView != null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            YandexBannerAuctionParam(
                activity = activity,
                bannerFormat = bannerFormat,
                adUnit = adUnit,
            )
        }
    }

    override fun load(adParams: YandexBannerAuctionParam) {
        val adUnitId = adParams.adUnitId
            ?: return emitEvent(AdEvent.LoadFailed(BidonError.IncorrectAdUnit(demandId = demandId, message = "adUnitId")))

        val adRequest = AdRequest.Builder(adUnitId)
            .apply {
                when (adParams.adUnit.bidType) {
                    BidType.RTB -> {
                        val signalData = adParams.signalData
                            ?: return emitEvent(AdEvent.LoadFailed(BidonError.IncorrectAdUnit(demandId = demandId, message = "signalData")))
                        setBiddingData(signalData)
                    }

                    BidType.CPM -> Unit
                }
            }
            .build()

        val bannerView = BannerAdView(adParams.activity).also { this.bannerView = it }
        val adSize = adParams.bannerFormat.toYandexBannerSize(adParams.activity)
        bannerView.setAdSize(adSize)
        bannerView.setBannerAdEventListener(
            object : BannerAdEventListener {
                override fun onAdLoaded() {
                    this@YandexBannerImpl.bannerView = bannerView
                    val ad = getAd() ?: return
                    emitEvent(AdEvent.Fill(ad))
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    logError(TAG, "onAdFailedToLoad: ${error.code} ${error.description}. $this", BidonError.NoFill(demandId))
                    emitEvent(AdEvent.LoadFailed(BidonError.NoFill(demandId)))
                }

                override fun onAdClicked() {
                    logInfo(TAG, "onAdClicked: $this")
                    val ad = getAd() ?: return
                    emitEvent(AdEvent.Clicked(ad))
                }

                override fun onImpression(impressionData: ImpressionData?) {
                    logInfo(TAG, "onImpression: $this")
                    val ad = getAd() ?: return
                    emitEvent(
                        AdEvent.PaidRevenue(
                            ad = ad,
                            adValue = impressionData.asBidonAdValue()
                        )
                    )
                }
            }
        )
        bannerView.loadAd(adRequest)
    }

    override fun getAdView(): AdViewHolder? = bannerView?.let { AdViewHolder(it) }

    override fun destroy() {
        logInfo(TAG, "destroy: $this")
        bannerView?.destroy()
        bannerView = null
    }
}

private const val TAG = "YandexBannerImpl"