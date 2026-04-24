package org.bidon.gma.impl

import android.annotation.SuppressLint
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import org.bidon.gma.GmaBannerAuctionParams
import org.bidon.gma.asBidonError
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

    private var adView: AdView? = null
    private var bannerAd: BannerAd? = null

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
            val adView = AdView(adParams.activity.applicationContext).also {
                this.adView = it
            }
            val adSize = adParams.adSize
            val bannerAdRequest = BannerAdRequest.Builder(adUnitId, adSize).build()

            adView.loadAd(
                bannerAdRequest,
                object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) {
                        logInfo(TAG, "onAdLoaded: $this")
                        bannerAd = ad
                        isAdReadyToShow = true
                        ad.adEventCallback = object : BannerAdEventCallback {
                            override fun onAdImpression() {
                                logInfo(TAG, "onAdImpression: $this")
                                // tracked impression/shown by BannerView
                            }

                            override fun onAdClicked() {
                                logInfo(TAG, "onAdClicked: $this")
                                getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
                            }

                            override fun onAdShowedFullScreenContent() {
                                // no-op
                            }

                            override fun onAdDismissedFullScreenContent() {
                                logInfo(TAG, "onAdDismissedFullScreenContent: $this")
                                getAd()?.let { emitEvent(AdEvent.Closed(it)) }
                            }

                            override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                                logInfo(TAG, "onAdFailedToShowFullScreenContent: $this")
                            }

                            override fun onAdPaid(adValue: AdValue) {
                                getAd()?.let {
                                    emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue()))
                                }
                            }
                        }
                        getAd()?.let { emitEvent(AdEvent.Fill(ad = it)) }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        logInfo(TAG, "onAdFailedToLoad: $error. $this")
                        emitEvent(AdEvent.LoadFailed(error.asBidonError()))
                    }
                }
            )
        }
    }

    override fun getAdView(): AdViewHolder? = adView?.let { AdViewHolder(networkAdview = it) }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        bannerAd?.adEventCallback = null
        adView?.destroy()
        adView = null
        bannerAd = null
    }
}

private const val TAG = "GmaBanner"
