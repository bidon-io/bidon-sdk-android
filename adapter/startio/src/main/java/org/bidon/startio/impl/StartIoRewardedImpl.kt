package org.bidon.startio.impl

import com.startapp.sdk.adsbase.StartAppAd
import org.bidon.sdk.adapter.AdSource

internal class StartIoRewardedImpl :
    StartIoFullscreenAdImpl(),
    AdSource.Rewarded<StartIoFullscreenAuctionParams> {

    override val tag: String = TAG
    override val adMode: StartAppAd.AdMode = StartAppAd.AdMode.REWARDED_VIDEO

    override val isAdReadyToShow: Boolean
        get() = super.isAdReadyToShow
}

private const val TAG = "StartIoRewardedImpl"