package org.bidon.amazon.impl

import android.content.Context
import android.view.View
import com.amazon.device.ads.DTBAdBannerListener
import com.amazon.device.ads.DTBAdView
import org.bidon.amazon.SlotType
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl

internal class AmazonBannerImpl(
    private val slots: Map<SlotType, List<String>>
) : AdSource.Banner<BannerAuctionParams>,
    Mode.Bidding,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var adView: DTBAdView? = null
    private val obtainToken: ObtainTokenUseCase get() = ObtainTokenUseCase()

    override val isAdReadyToShow: Boolean
        get() = adView?.isAdViewVisible == true

    override suspend fun getToken(context: Context): String? {
        return obtainToken(slots, BannerFormat.Banner)
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
