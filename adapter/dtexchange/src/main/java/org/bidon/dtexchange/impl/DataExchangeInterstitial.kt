package org.bidon.dtexchange.impl

import android.app.Activity
import com.fyber.inneractive.sdk.external.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bidon.dtexchange.ext.asBidonError
import org.bidon.sdk.adapter.*
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
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

    private var inneractiveAdSpot: InneractiveAdSpot? = null
    private val adRequestListener by lazy {
        object : InneractiveAdSpot.RequestListener {
            override fun onInneractiveSuccessfulAdRequest(inneractiveAdSpot: InneractiveAdSpot?) {
                this@DataExchangeInterstitial.inneractiveAdSpot = inneractiveAdSpot
                markBidFinished(
                    ecpm = null,
                    roundStatus = RoundStatus.Successful,
                )
                adEvent.tryEmit(
                    AdEvent.Bid(
                        AuctionResult(
                            ecpm = 0.0,
                            adSource = this@DataExchangeInterstitial,
                        )
                    )
                )
            }

            override fun onInneractiveFailedAdRequest(
                inneractiveAdSpot: InneractiveAdSpot?,
                inneractiveErrorCode: InneractiveErrorCode?
            ) {
                markBidFinished(
                    ecpm = null,
                    roundStatus = inneractiveErrorCode.asBidonError().asRoundStatus(),
                )
                adEvent.tryEmit(AdEvent.LoadFailed(inneractiveErrorCode.asBidonError()))
            }
        }
    }

    private val impressionListener by lazy {
        object : InneractiveFullscreenAdEventsListenerWithImpressionData {
            override fun onAdImpression(adSpot: InneractiveAdSpot?, impressionData: ImpressionData?) {
                TODO("Not yet implemented")
            }

            override fun onAdImpression(adSpot: InneractiveAdSpot?) {
                TODO("Not yet implemented")
            }

            override fun onAdClicked(adSpot: InneractiveAdSpot?) {
                TODO("Not yet implemented")
            }

            override fun onAdWillCloseInternalBrowser(adSpot: InneractiveAdSpot?) {
                TODO("Not yet implemented")
            }

            override fun onAdWillOpenExternalApp(adSpot: InneractiveAdSpot?) {
                TODO("Not yet implemented")
            }

            override fun onAdEnteredErrorState(
                adSpot: InneractiveAdSpot?,
                adDisplayError: InneractiveUnitController.AdDisplayError?
            ) {
                TODO("Not yet implemented")
            }

            override fun onAdDismissed(adSpot: InneractiveAdSpot?) {
                TODO("Not yet implemented")
            }
        }
    }

    override val ad: Ad?
        get() = TODO("Not yet implemented")
    override val adEvent = MutableSharedFlow<AdEvent>(Int.MAX_VALUE)
    override val isAdReadyToShow: Boolean
        get() = inneractiveAdSpot?.isReady == true

    override fun getAuctionParams(
        activity: Activity,
        pricefloor: Double,
        timeout: Long,
        lineItems: List<LineItem>,
        onLineItemConsumed: (LineItem) -> Unit
    ): Result<AdAuctionParams> {
        return DataExchangeAdAuctionParams(spotId = "").asSuccess()
    }

    override suspend fun bid(adParams: DataExchangeAdAuctionParams): AuctionResult {
        logInfo(Tag, "Starting with $adParams: $this")
        //markBidStarted(adParams.lineItem.adUnitId)
        val spot = InneractiveAdSpotManager.get().createSpot()
        val controller = InneractiveFullscreenUnitController()
        val videoController = InneractiveFullscreenVideoContentController()
        controller.addContentController(videoController)
        controller.eventsListener = impressionListener
        spot.addUnitController(controller)

        val adRequest = InneractiveAdRequest(adParams.spotId)
        spot.requestAd(adRequest)

        // InneractiveAdManager.setMuteVideo(true)

        spot.setRequestListener(adRequestListener)
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