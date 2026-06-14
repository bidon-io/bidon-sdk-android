package org.bidon.gma.impl

import android.annotation.SuppressLint
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.OnPaidEventListener
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
import org.bidon.sdk.ads.AdType
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

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return getAdAuctionParams(auctionParamsScope, AdType.Banner)
    }

    @SuppressLint("MissingPermission")
    override fun load(adParams: GmaBannerAuctionParams) {
        logInfo(TAG, "Starting with $adParams")
        val adUnitId = when (adParams) {
            is GmaBannerAuctionParams.Network -> adParams.adUnitId
        } ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "adUnitId")
                )
            )
            return
        }
        adParams.activity.runOnUiThread {
            val adView = AdView(adParams.activity).also {
                adView = it
            }
            val bannerAdRequest = BannerAdRequest.Builder(adUnitId, adParams.adSize).build()
            val requestListener = object : AdLoadCallback<BannerAd> {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    logInfo(TAG, "onAdFailedToLoad: $loadAdError. $this")
                    emitEvent(AdEvent.LoadFailed(loadAdError.asBidonError()))
                }

                override fun onAdLoaded(ad: BannerAd) {
                    logInfo(TAG, "onAdLoaded: $this")
                    isAdReadyToShow = true
                    adView.onPaidEventListener = OnPaidEventListener { adValue ->
                        getAd()?.let {
                            emitEvent(AdEvent.PaidRevenue(it, adValue.asBidonAdValue()))
                        }
                    }
                    getAd()?.let { emitEvent(AdEvent.Fill(ad = it)) }
                }
            }
            adView.loadAd(bannerAdRequest, requestListener)
        }
    }

    override fun getAdView(): AdViewHolder? = adView?.let { AdViewHolder(it) }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        adView?.onPaidEventListener = null
        adView = null
    }
}

private const val TAG = "GmaBanner"
