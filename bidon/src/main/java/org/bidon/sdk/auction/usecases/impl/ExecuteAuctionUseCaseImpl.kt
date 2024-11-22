package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandStatus
import org.bidon.sdk.stats.models.asDemandStatus
import java.util.LinkedList

internal class ExecuteAuctionUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val requestAdUnit: RequestAdUnitUseCase,
) : ExecuteAuctionUseCase {

    private var adUnitQueue: LinkedList<AdUnit> = LinkedList()

    override suspend fun invoke(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        auctionTimeout: Long,
        adUnits: List<AdUnit>,
        resultsCollector: ResultsCollector,
    ) {
        runCatching {
            val result = withTimeoutOrNull(auctionTimeout) {
                logInfo(TAG, "Starting auction | ID: $auctionId, Timeout: ${auctionTimeout}ms, Pricefloor: $pricefloor")

                adUnitQueue = LinkedList(adUnits)
                var index = 1

                while (adUnitQueue.isNotEmpty()) {
                    val adUnit = adUnitQueue.peek() ?: break
                    logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index request -> $adUnit")

                    if (adUnit.pricefloor < pricefloor) {
                        logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index pricefloor is below auction pricefloor")
                        collectResult(adUnit, getBelowPriceFloorResult(adUnit), resultsCollector)
                        adUnitQueue.poll()
                        index++
                        continue
                    }

                    val adapter = findAdapter(adUnit.demandId)
                    if (adapter == null) {
                        logInfo(TAG, "Auction ID: $auctionId | Adapter not found for Demand ID '${adUnit.demandId}'")
                        collectResult(adUnit, DemandStatus.UnknownAdapter, resultsCollector)
                        adUnitQueue.poll()
                        index++
                        continue
                    }

                    adapter.applyRegulation()
                    val adSource = adapter.getAdSources(demandAd.adType)
                    if (adSource == null) {
                        logInfo(TAG, "Auction ID: $auctionId | AdSource not found for Demand ID '${adUnit.demandId}'")
                        collectResult(adUnit, DemandStatus.UnknownAdapter, resultsCollector)
                        adUnitQueue.poll()
                        index++
                        continue
                    }

                    adSource.applyParams(
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionPricefloor = pricefloor,
                        auctionConfigurationId = auctionConfigurationId,
                        auctionConfigurationUid = auctionConfigurationUid,
                        demandId = adapter.demandId,
                        adTypeParam = adTypeParam,
                        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                    )

                    val startTime = System.currentTimeMillis()
                    logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index request -> ${adUnit.demandId}")
                    val requestAdUnitResult =
                        requestAdUnit.invoke(adSource, adUnit, adTypeParam, pricefloor)
                    resultsCollector.add(requestAdUnitResult)
                    val endTime = System.currentTimeMillis()
                    logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index request result -> ${requestAdUnitResult.demandStatus.code}, time taken -> ${endTime - startTime}ms")

                    val currentAdUnit = adUnitQueue.poll()
                    val nextAdUnit = adUnitQueue.peek()
                    if (nextAdUnit != null && shouldStopRequests(requestAdUnitResult, nextAdUnit)) {
                        logInfo(TAG, "Auction ID: $auctionId | Stopping requests after AdUnit #$index due to eCPM")
                        finishAuction(resultsCollector) { statusAdUnit ->
                            getBelowPriceFloorResult(statusAdUnit)
                        }
                        break
                    }

                    index++
                }

                logInfo(TAG, "Auction ID: $auctionId | Auction process finished")
                resultsCollector.getRoundResults().let { roundResult ->
                    (roundResult as? AuctionResult.Results)?.demandResults.orEmpty()
                }
            }

            if (result.isNullOrEmpty()) {
                finishAuction(resultsCollector) { DemandStatus.FillTimeoutReached }
                logInfo(TAG, "Auction ID: $auctionId | Auction ended by timeout: ${auctionTimeout}ms")
            }
        }.onFailure { error ->
            val status = error.asBidonErrorOrUnspecified().asDemandStatus()
            finishAuction(resultsCollector) { status }
            logError(TAG, "Auction ID: $auctionId | Error occurred during auction", error)
        }
    }

    private fun getBelowPriceFloorResult(adUnit: AdUnit): DemandStatus {
        return when (adUnit.bidType) {
            BidType.RTB -> DemandStatus.Lose
            BidType.CPM -> DemandStatus.BelowPricefloor
        }
    }

    private fun findAdapter(demandId: String): Adapter? =
        adaptersSource.adapters.find { it.demandId.demandId == demandId }

    private fun collectResult(adUnit: AdUnit, status: DemandStatus, resultsCollector: ResultsCollector) {
        resultsCollector.add(DemandResult.DemandFailed(adUnit, status))
    }

    private fun finishAuction(
        resultsCollector: ResultsCollector,
        statusProvider: (statusAdUnit: AdUnit) -> DemandStatus
    ) {
        adUnitQueue.forEach { adUnit ->
            resultsCollector.add(DemandResult.DemandFailed(adUnit, statusProvider(adUnit)))
        }
    }

    private fun shouldStopRequests(demandResult: DemandResult, nextAdUnit: AdUnit): Boolean {
        return demandResult.demandStatus == DemandStatus.Successful &&
                demandResult.adSource.getStats().ecpm >= nextAdUnit.pricefloor
    }

    private fun AdSource<AdAuctionParams>.applyParams(
        demandAd: DemandAd,
        auctionId: String,
        auctionPricefloor: Double,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        demandId: DemandId,
        adTypeParam: AdTypeParam,
        externalWinNotificationsEnabled: Boolean,
    ) {
        val adSource = this
        adSource.addDemandId(demandId)
        adSource.addAuctionInfo(
            auctionId = auctionId,
            auctionPricefloor = auctionPricefloor,
            auctionConfigurationId = auctionConfigurationId,
            auctionConfigurationUid = auctionConfigurationUid,
        )
        adSource.setAuctionConfigurationId(auctionConfigurationId)
        adSource.setAuctionConfigurationUid(auctionConfigurationUid)
        adSource.setDemandAd(demandAd)
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.setExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }

    private fun Adapter.getAdSources(adType: AdType): AdSource<AdAuctionParams>? {
        return runCatching {
            when (adType) {
                AdType.Interstitial -> (this as? AdProvider.Interstitial<AdAuctionParams>)?.interstitial()
                AdType.Rewarded -> (this as? AdProvider.Rewarded<AdAuctionParams>)?.rewarded()
                AdType.Banner -> (this as? AdProvider.Banner<AdAuctionParams>)?.banner()
            }
        }.onFailure {
            logError(TAG, "Failed to create ad source for type: $adType", it)
        }.getOrNull()
    }

    private fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType {
        return when (this) {
            is AdTypeParam.Banner -> {
                StatisticsCollector.AdType.Banner(
                    format = when (bannerFormat) {
                        BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                        BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                        BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                        BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                    }
                )
            }

            is AdTypeParam.Interstitial -> StatisticsCollector.AdType.Interstitial
            is AdTypeParam.Rewarded -> StatisticsCollector.AdType.Rewarded
        }
    }
}

private const val TAG = "ExecuteAuctionUseCase"