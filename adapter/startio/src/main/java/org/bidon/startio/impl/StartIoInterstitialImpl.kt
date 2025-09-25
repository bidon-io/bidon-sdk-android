package org.bidon.startio.impl

import com.startapp.sdk.adsbase.StartAppAd
import org.bidon.sdk.adapter.AdSource

internal class StartIoInterstitialImpl :
    StartIoFullscreenAdImpl(),
    AdSource.Interstitial<StartIoFullscreenAuctionParams> {

    override val tag: String = TAG
    override val adMode: StartAppAd.AdMode = StartAppAd.AdMode.AUTOMATIC

    override val isAdReadyToShow: Boolean
        get() = super.isAdReadyToShow
}

private const val TAG = "StartIoInterstitialImpl"
