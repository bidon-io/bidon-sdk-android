package org.bidon.startio.impl

import android.app.Activity
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.startio.StartIoDemandId

internal abstract class StartIoFullscreenAdImpl :
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var startAppAd: StartAppAd? = null

    protected abstract val tag: String
    protected abstract val adMode: StartAppAd.AdMode

    open val isAdReadyToShow: Boolean
        get() = startAppAd?.state == Ad.AdState.READY

    private var loadListener = object : AdEventListener {
        override fun onReceiveAd(ad: Ad) {
            logInfo(tag, "onReceiveAd")
            getAd()?.let { emitEvent(AdEvent.Fill(it)) }
        }

        override fun onFailedToReceiveAd(ad: Ad?) {
            val errorMessage = "onFailedToReceiveAd: ${ad?.errorMessage}"
            logInfo(tag, errorMessage)
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.Unspecified(StartIoDemandId, message = errorMessage)
                )
            )
        }
    }

    private var showListener = object : AdDisplayListener {
        override fun adHidden(ad: Ad?) {
            logInfo(tag, "adHidden: $this")
            getAd()?.let { emitEvent(AdEvent.Closed(it)) }
        }

        override fun adDisplayed(ad: Ad?) {
            logInfo(tag, "adDisplayed")
            getAd()?.let {
                emitEvent(AdEvent.Shown(it))
            }
        }

        override fun adClicked(ad: Ad?) {
            logInfo(tag, "onAdClicked")
            getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
        }

        override fun adNotDisplayed(ad: Ad?) {
            val errorMessage =
                "onFailedToReceiveAd: ${ad?.errorMessage}. Reason: ${ad?.notDisplayedReason?.name}"
            logInfo(tag, "onFailedToReceiveAd: $errorMessage")
            emitEvent(
                AdEvent.ShowFailed(
                    BidonError.Unspecified(
                        StartIoDemandId,
                        message = errorMessage
                    )
                )
            )
        }
    }

    open fun show(activity: Activity) {
        if (isAdReadyToShow) {
            startAppAd?.showAd(showListener)
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.AdNotReady))
        }
    }

    open fun load(adParams: StartIoFullscreenAuctionParams) {
        if (adParams.payload == null) {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "payload")
                )
            )
            return
        }
        val startAppAd = StartAppAd(adParams.context).also {
            this.startAppAd = it
        }
        startAppAd.loadAd(
            adMode,
            loadListener,
            adParams.payload
        )
    }

    open fun destroy() {
        startAppAd = null
    }

    open fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return ObtainAuctionParamUseCase().getFullscreenParam(auctionParamsScope)
    }
}
