package org.bidon.sdk.adapter

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.stats.StatisticsCollector

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
sealed interface AdSource<T : AdAuctionParams> : StatisticsCollector {
    val demandId: DemandId
    val ad: Ad?
    val adEvent: Flow<AdEvent>
    val isAdReadyToShow: Boolean

    /**
     * Applovin needs Activity instance for interstitial 🤦‍️
     */
    fun show(activity: Activity)
    fun destroy()
    fun getAuctionParam(adAuctionParamsCatching: AdAuctionParamSource): Result<AdAuctionParams>

    interface Interstitial<T : AdAuctionParams> : AdSource<T>
    interface Rewarded<T : AdAuctionParams> : AdSource<T>
    interface Banner<T : AdAuctionParams> : AdSource<T> {
        fun getAdView(): AdViewHolder
    }
}
