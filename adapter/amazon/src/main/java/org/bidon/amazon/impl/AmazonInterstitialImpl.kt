package org.bidon.amazon.impl

import android.app.Activity
import android.content.Context
import android.view.View
import com.amazon.device.ads.AdError
import com.amazon.device.ads.DTBAdBannerListener
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdInterstitial
import com.amazon.device.ads.DTBAdInterstitialListener
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import com.amazon.device.ads.DTBAdUtil
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

internal class AmazonInterstitialImpl : AdSource.Interstitial<FullscreenAuctionParams>,
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
            FullscreenAuctionParams(
                activity = activity,
                slotUuid = requireNotNull(json?.optString("slot_uuid")) {
                    "SlotUid is required for Amazon banner ad"
                },
                price = pricefloor
            )
        }
    }

    override fun load(adParams: FullscreenAuctionParams) {
//        val interstitial = PublisherInterstitialAd()
//        val loader = DTBAdRequest()
//        loader.setSizes(DTBAdSize.DTBInterstitialAdSize(adParams.slotUuid))
//        loader.loadAd(object : DTBAdCallback {
//            override fun onFailure(adError: AdError) {
//                logError(TAG, "Error while loading ad: ${adError.code} ${adError.message}", BidonError.NoFill(demandId))
//                /**Please implement the logic to send ad request without our parameters if you want to
//                 * show ads from other ad networks when Amazon ad request fails */
//                emitEvent(AdEvent.LoadFailed(BidonError.NoFill(demandId)))
//            }
//
//            override fun onSuccess(dtbAdResponse: DTBAdResponse) {
//                val custParams = dtbAdResponse.defaultDisplayAdsRequestCustomParams
//                logInfo(TAG, "Ad loaded with custParams: $custParams")
//                //Loop through custParams and forward the targeting to your ad server
//
//                //Build the ad request to your ad server
//                val interstitialAd = DTBAdInterstitial(adParams.activity, object : DTBAdInterstitialListener{
//                    override fun onAdLoaded(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onAdFailed(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onAdClicked(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onAdLeftApplication(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onAdOpen(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onAdClosed(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onImpressionFired(p0: View?) {
//                        TODO("Not yet implemented")
//                    }
//
//                })
//                interstitialAd.fetchAd(----)
//                val adRequest:  PublisherAdRequest = DTBAdUtil.INSTANCE.createPublisherAdRequestBuilder(dtbAdResponse)).build();
//                interstitialAd.loadAd(adRequest);
//            }
//        })
//
//        val adView = DTBAdView(adParams.activity.applicationContext, object : DTBAdBannerListener {
//            override fun onAdLoaded(view: View?) {
//            }
//
//            override fun onAdFailed(view: View?) {
//            }
//
//            override fun onAdClicked(view: View?) {
//            }
//
//            override fun onAdLeftApplication(view: View?) {
//            }
//
//            override fun onAdOpen(view: View?) {
//            }
//
//            override fun onAdClosed(view: View?) {
//            }
//
//            override fun onImpressionFired(view: View?) {
//            }
//        })
        // TODO adView.fetchAd()
    }

    override fun show(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun destroy() {
        TODO("Not yet implemented")
    }
}

private const val TAG = "AmazonInterstitialImpl"