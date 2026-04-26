package org.bidon.gma.impl

import android.annotation.SuppressLint
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.banner.BannerView
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadError
import org.bidon.gma.GmaBannerAuctionParams
import org.bidon.gma.asBidonError
import org.bidon.gma.ext.GmaAdValue
import org.bidon.gma.ext.asBidonAdValue
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class GmaBannerImpl(
    private val getAdAuctionParams: GetAdAuctionParamsUseCase = GetAdAuctionParamsUseCase(),
) : AdSource.Banner<GmaBannerAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    override var isAdReadyToShow: Boolean = false

    private var bannerView: BannerView? = null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return getAdAuctionParams(auctionParamsScope, demandAd.adType)
    }

    @SuppressLint("MissingPermission")
    override fun load(adParams: GmaBannerAuctionParams) {
        logInfo(TAG, "Starting with $adParams")
        val adUnitId: String = when (adParams) {
            is GmaBannerAuctionParams.Network -> adParams.adUnitId
        } ?: run {
            emitEvent(AdEvent.LoadFailed(BidonError.IncorrectAdUnit(demandId = demandId, message = "adUnitId")))
            return
        }
        adParams.activity.runOnUiThread {
            val view = BannerView(adParams.activity.applicationContext).also {
                bannerView = it
            }
            val bannerAdRequest = BannerAdRequest.Builder(adUnitId, adParams.adSize).build()
            view.loadAd(bannerAdRequest, object : AdLoadCallback<BannerAd> {
                override fun onAdFailedToLoad(error: AdLoadError) {
                    logInfo(TAG, "onAdFailedToLoad: $error")
                    emitEvent(AdEvent.LoadFailed(error.asBidonError()))
                }

                override fun onAdLoaded(ad: BannerAd) {
                    logInfo(TAG, "onAdLoaded: $ad")
                    isAdReadyToShow = true
                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdImpression() {
                            logInfo(TAG, "onAdImpression")
                            // Impression is tracked by BannerView
                        }

                        override fun onAdClicked() {
                            logInfo(TAG, "onAdClicked")
                            getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
                        }

                        override fun onAdPaid(adValue: GmaAdValue) {
                            logInfo(TAG, "onAdPaid: ${adValue.valueMicros}")
                            getAd()?.let { emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue())) }
                        }
                    }
                    getAd()?.let { emitEvent(AdEvent.Fill(it)) }
                }
            })
        }
    }

    override fun getAdView(): AdViewHolder? = bannerView?.let { AdViewHolder(networkAdview = it) }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        bannerView = null
        isAdReadyToShow = false
    }
}

private const val TAG = "GmaBanner"
