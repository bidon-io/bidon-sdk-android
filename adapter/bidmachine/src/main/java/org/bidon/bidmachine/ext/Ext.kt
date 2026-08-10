package org.bidon.bidmachine.ext

import io.bidmachine.AdContentType
import io.bidmachine.AdPlacementConfig
import io.bidmachine.BannerAdSize
import io.bidmachine.BidMachine
import org.bidon.bidmachine.BMAuctionResult
import org.bidon.bidmachine.BuildConfig
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = BidMachine.VERSION

internal fun AdTypeParam.toAdPlacementConfig(placementId: String?): AdPlacementConfig =
    when (this) {
        is AdTypeParam.Banner -> AdPlacementConfig.bannerBuilder(bannerFormat.toBannerAdSize())
        is AdTypeParam.Interstitial -> AdPlacementConfig.interstitialBuilder(AdContentType.All)
        is AdTypeParam.Rewarded -> AdPlacementConfig.rewardedBuilder(AdContentType.All)
    }.apply {
        placementId?.let { withPlacementId(it) }
    }.build()

internal fun BannerFormat.toBannerAdSize(): BannerAdSize = when (this) {
    BannerFormat.Banner -> BannerAdSize.Banner
    BannerFormat.LeaderBoard -> BannerAdSize.Leaderboard
    BannerFormat.MRec -> BannerAdSize.MediumRectangle
    BannerFormat.Adaptive -> if (DeviceInfo.isTablet) BannerAdSize.Leaderboard else BannerAdSize.Banner
}

internal fun AdUnit.addCustomParams(auctionResult: BMAuctionResult) {
    val extra = extra ?: return
    auctionResult.customParams.forEach { (key, value) -> extra.put(key, value) }
}