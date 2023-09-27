package org.bidon.amazon.impl

import android.content.Context
import android.view.View
import com.amazon.device.ads.AdError
import com.amazon.device.ads.DTBAdBannerListener
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import com.amazon.device.ads.DTBAdView
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.banner.helper.getHeightDp
import org.bidon.sdk.ads.banner.helper.getWidthDp
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class AmazonBannerImpl : AdSource.Banner<BannerAuctionParams>,
    Mode.Bidding,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    override val isAdReadyToShow: Boolean
        get() = TODO("Not yet implemented")

    override suspend fun getToken(context: Context): String? {
        TODO("Not yet implemented")
    }

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            BannerAuctionParams(
                activity = activity,
                bannerFormat = bannerFormat,
                slotUuid = requireNotNull(json?.optString("slot_uuid")) {
                    "SlotUid is required for Amazon banner ad"
                },
                price = pricefloor
            )
        }
    }

    override fun load(adParams: BannerAuctionParams) {
        val loader = DTBAdRequest()
        loader.setSizes(
            DTBAdSize(
                adParams.bannerFormat.getWidthDp(),
                adParams.bannerFormat.getHeightDp(),
                adParams.slotUuid
            )
        )
        loader.loadAd(object : DTBAdCallback {
            override fun onFailure(adError: AdError) {
                logError(TAG, "Error while loading ad: ${adError.code} ${adError.message}", BidonError.NoFill(demandId))
                /**Please implement the logic to send ad request without our parameters if you want to
                 * show ads from other ad networks when Amazon ad request fails */
                emitEvent(AdEvent.LoadFailed(BidonError.NoFill(demandId)))
            }

            override fun onSuccess(dtbAdResponse: DTBAdResponse) {
                val custParams = dtbAdResponse.defaultDisplayAdsRequestCustomParams
                logInfo(TAG, "Ad loaded with custParams: $custParams")
                dtbAdResponse
                //Loop through custParams and forward the targeting to your ad server
            }
        })

        val adView = DTBAdView(adParams.activity.applicationContext, object : DTBAdBannerListener {
            override fun onAdLoaded(view: View?) {
            }

            override fun onAdFailed(view: View?) {
            }

            override fun onAdClicked(view: View?) {
            }

            override fun onAdLeftApplication(view: View?) {
            }

            override fun onAdOpen(view: View?) {
            }

            override fun onAdClosed(view: View?) {
            }

            override fun onImpressionFired(view: View?) {
            }
        })
        // TODO adView.fetchAd()
    }

    override fun getAdView(): AdViewHolder? {
        TODO("Not yet implemented")
    }

    override fun destroy() {
        TODO("Not yet implemented")
    }
}

private const val TAG = "AmazonBannerImpl"
