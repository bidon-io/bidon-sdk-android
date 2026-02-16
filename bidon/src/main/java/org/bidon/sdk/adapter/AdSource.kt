package org.bidon.sdk.adapter

import android.app.Activity
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.stats.StatisticsCollector

/**
 * Created by Bidon Team on 07/09/2022.
 */
public sealed interface AdSource<T : AdAuctionParams> : StatisticsCollector, AdEventFlow {
    public val isAdReadyToShow: Boolean

    public fun load(adParams: T)
    public fun destroy()
    public fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams>

    public interface Interstitial<T : AdAuctionParams> : AdSource<T> {
        /**
         * DTExchange, Applovin, UnityAds need [Activity] instance for displaying ad 🤦‍️
         */
        public fun show(activity: Activity)
    }
    public interface Rewarded<T : AdAuctionParams> : AdSource<T> {
        public fun show(activity: Activity)
    }
    public interface Banner<T : AdAuctionParams> : AdSource<T> {
        public fun getAdView(): AdViewHolder?
    }
}
