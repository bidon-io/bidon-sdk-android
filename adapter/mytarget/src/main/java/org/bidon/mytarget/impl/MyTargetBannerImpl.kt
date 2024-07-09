package org.bidon.mytarget.impl

import com.my.target.ads.MyTargetView
import com.my.target.common.models.IAdLoadingError
import org.bidon.mytarget.ext.asBidonError
import org.bidon.mytarget.ext.toAdSize
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.stats.models.BidType

class MyTargetBannerImpl :
    AdSource.Banner<MyTargetViewAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var adView: MyTargetView? = null
    private var adSize: MyTargetView.AdSize? = null

    override fun getAdView(): AdViewHolder? = adView?.let {
        val adSize = adSize ?: return null
        AdViewHolder(
            networkAdview = it,
            widthDp = adSize.width,
            heightDp = adSize.height
        )
    }

    override val isAdReadyToShow: Boolean
        get() = adView != null

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        val bannerFormat = auctionParamsScope.bannerFormat
        if (bannerFormat == BannerFormat.Adaptive && DeviceInfo.isTablet
            || bannerFormat == BannerFormat.LeaderBoard
        ) {
            throw BidonError.AdFormatIsNotSupported(demandId.demandId, bannerFormat)
        }
        adSize = bannerFormat.toAdSize()
        return auctionParamsScope {
            MyTargetViewAuctionParams(
                context = auctionParamsScope.activity,
                bannerFormat = bannerFormat,
                adUnit = auctionParamsScope.adUnit
            )
        }
    }

    override fun load(adParams: MyTargetViewAuctionParams) {
        adParams.slotId ?: run {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "slotId")
                )
            )
            return
        }
        val adView = MyTargetView(adParams.context).also {
            adView = it
        }
        adSize ?: adParams.bannerFormat.toAdSize()?.let {
            adView.setAdSize(it)
        }
        adView.setSlotId(adParams.slotId)
        adView.listener = object : MyTargetView.MyTargetViewListener {
            override fun onLoad(adView: MyTargetView) {
                logInfo(TAG, "onLoad: $this")
                emitEvent(AdEvent.Fill(getAd() ?: return))
            }

            override fun onNoAd(error: IAdLoadingError, adView: MyTargetView) {
                logInfo(TAG, "Error while loading ad: ${error.code} ${error.message}. $this")
                emitEvent(AdEvent.LoadFailed(error.asBidonError(adParams.bannerFormat)))
            }

            override fun onShow(adView: MyTargetView) {
                val ad = getAd() ?: return
                emitEvent(
                    AdEvent.PaidRevenue(
                        ad = ad,
                        adValue = AdValue(
                            adRevenue = ad.ecpm / 1000.0,
                            precision = Precision.Estimated,
                            currency = AdValue.USD,
                        )
                    )
                )
            }

            override fun onClick(adView: MyTargetView) {
                logInfo(TAG, "onClick: $this")
                emitEvent(AdEvent.Clicked(getAd() ?: return))
            }
        }
        if (adParams.adUnit.bidType == BidType.RTB) {
            adParams.payload ?: run {
                emitEvent(
                    AdEvent.LoadFailed(
                        BidonError.IncorrectAdUnit(demandId = demandId, message = "payload")
                    )
                )
                return
            }
            adView.loadFromBid(adParams.payload)
        } else {
            adView.load()
        }
    }

    override fun destroy() {
        adView?.destroy()
        adView = null
        adSize = null
    }
}

private const val TAG = "MyTargetBannerImpl"