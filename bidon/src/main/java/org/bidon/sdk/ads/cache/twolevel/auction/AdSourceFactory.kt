package org.bidon.sdk.ads.cache.twolevel.auction

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.ext.asStatisticAdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.impl.logError

/**
 * Utilities for configuring and managing AdSource instances.
 */
internal object AdSourceFactory {
    /**
     * Apply auction parameters to AdSource.
     */
    fun applyParams(
        adSource: AdSource<AdAuctionParams>,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        demandAd: DemandAd,
        pricefloor: Double,
        adTypeParam: AdTypeParam,
    ) {
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = pricefloor,
        )
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }
}

/**
 * Destroy AdSource with guaranteed execution even during cancellation.
 */
internal suspend fun AdSource<*>.safeDestroy(demandId: String) {
    withContext(NonCancellable) {
        try {
            destroy()
        } catch (e: Exception) {
            logError("[TwoLevelCache]", "AdSource.destroy() failed: demandId=$demandId", e)
        }
    }
}
