package org.bidon.dtexchange.impl

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import org.bidon.sdk.adapter.*
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.utils.ext.asSuccess

/**
 * Created by Aleksei Cherniaev on 28/02/2023.
 */
internal class DataExchangeInterstitial(
    override val demandId: DemandId,
    private val demandAd: DemandAd,
    private val roundId: String,
    private val auctionId: String
) : AdSource.Interstitial<DataExchangeAdAuctionParams>,
    StatisticsCollector by StatisticsCollectorImpl(
        auctionId = auctionId,
        roundId = roundId,
        demandId = demandId
    ) {

    override val ad: Ad?
        get() = TODO("Not yet implemented")
    override val adEvent: Flow<AdEvent>
        get() = TODO("Not yet implemented")
    override val isAdReadyToShow: Boolean
        get() = TODO("Not yet implemented")

    override fun getAuctionParams(
        activity: Activity,
        pricefloor: Double,
        timeout: Long,
        lineItems: List<LineItem>,
        onLineItemConsumed: (LineItem) -> Unit
    ): Result<AdAuctionParams> {
        return DataExchangeAdAuctionParams.asSuccess()
    }

    override suspend fun bid(adParams: DataExchangeAdAuctionParams): AuctionResult {
        logInfo(Tag, "Starting with $adParams: $this")
        //markBidStarted(adParams.lineItem.adUnitId)

        TODO("Not yet implemented")
    }

    override suspend fun fill(): Result<Ad> {
        TODO("Not yet implemented")
    }

    override fun show(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun destroy() {
        TODO("Not yet implemented")
    }
}

private const val Tag = "DataExchangeInterstitial"