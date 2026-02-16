package org.bidon.sdk.ads.ext

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.AdTypeParam

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal fun AdTypeParam.asAdType() = when (this) {
    is AdTypeParam.Banner -> AdType.Banner
    is AdTypeParam.Interstitial -> AdType.Interstitial
    is AdTypeParam.Rewarded -> AdType.Rewarded
}