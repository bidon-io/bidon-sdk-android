package org.bidon.sdk.ads.impl

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.models.BannerRequestBody.Companion.asStatBannerFormat
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector

/**
 * Created by Aleksei Cherniaev on 27/06/2023.
 */
internal class WinLossNotifierHelper {
    /**
     * For banner
     */
    fun notifyWin(
        adSource: AdSource<*>?,
        adType: AdType,
        bannerFormat: BannerFormat? = null,
    ) {
        logInfo(Tag, "Notify Win invoked")
        if (adSource == null) {
            logInfo(Tag, "Notify Win skipped. No winner found.")
        } else {
            adSource.sendWin(adType.asStatisticAdType(bannerFormat))
        }
    }

    /**
     * For banner
     */
    fun notifyLoss(
        winnerDemandId: String,
        winnerEcpm: Double,
        adSource: AdSource<*>?,
        adType: AdType,
        bannerFormat: BannerFormat? = null,
    ) {
        logInfo(Tag, "Notify Loss invoked with Winner($winnerDemandId, $winnerEcpm)")
        if (adSource == null) {
            logInfo(Tag, "Notify Loss skipped. No winner found.")
        } else {
            adSource.sendLoss(
                winnerDemandId = winnerDemandId,
                winnerEcpm = winnerEcpm,
                adType = adType.asStatisticAdType(bannerFormat)
            )
        }
    }

    private fun AdType.asStatisticAdType(bannerFormat: BannerFormat? = null) = when (this) {
        AdType.Banner -> {
            StatisticsCollector.AdType.Banner(
                format = requireNotNull(bannerFormat).asStatBannerFormat()
            )
        }

        AdType.Interstitial -> {
            StatisticsCollector.AdType.Interstitial
        }

        AdType.Rewarded -> {
            StatisticsCollector.AdType.Rewarded
        }
    }
}

private const val Tag = "WinLossNotifier"