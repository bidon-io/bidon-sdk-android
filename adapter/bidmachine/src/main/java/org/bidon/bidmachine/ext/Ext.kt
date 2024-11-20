package org.bidon.bidmachine.ext

import io.bidmachine.AdsFormat
import io.bidmachine.BidMachine
import org.bidon.bidmachine.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = BidMachine.VERSION

internal fun AdTypeParam.toBidmachineAdFormat(): AdsFormat = when (this) {
    is AdTypeParam.Banner -> this.bannerFormat.toBidmachineBannerAdFormat()
    is AdTypeParam.Interstitial -> AdsFormat.Interstitial
    is AdTypeParam.Rewarded -> AdsFormat.Rewarded
}

private fun BannerFormat.toBidmachineBannerAdFormat(): AdsFormat = when (this) {
    BannerFormat.Banner, BannerFormat.Adaptive -> AdsFormat.Banner_320x50
    BannerFormat.LeaderBoard -> AdsFormat.Banner_728x90
    BannerFormat.MRec -> AdsFormat.Banner_300x250
}