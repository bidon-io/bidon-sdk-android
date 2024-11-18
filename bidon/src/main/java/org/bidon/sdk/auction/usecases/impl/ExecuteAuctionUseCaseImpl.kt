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
import org.bidon.sdk.auction.models.TokenInfo
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
        tokens: Map<String, TokenInfo>
    ) {
        runCatching {
            val result = withTimeoutOrNull(auctionTimeout) {
                logInfo(TAG, "Starting auction | ID: $auctionId, Timeout: ${auctionTimeout}ms, Pricefloor: $pricefloor")

                adUnitQueue = LinkedList(adUnits)
                var index = 1
                while (adUnitQueue.isNotEmpty()) {
                    val adUnit = adUnitQueue.peek()
                    if (adUnit == null) {
                        logInfo(TAG, "Auction ID: $auctionId | All adUnits processed")
                        break
                    }

                    logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index request -> $adUnit")

                    val tokenInfo: TokenInfo? = tokens[adUnit.demandId]

                    if (adUnit.pricefloor < pricefloor) {
                        logInfo(TAG, "Auction ID: $auctionId | Skipping AdUnit #$index due to pricefloor: Required: $pricefloor, Actual: ${adUnit.pricefloor}")
                        adUnitQueue.remove()
                        resultsCollector.add(
                            getBelowPriceFloorResult(
                                adUnit = adUnit,
                                tokenInfo = tokenInfo
                            )
                        )
                        index++
                        continue
                    }

                    val adapter =
                        adaptersSource.adapters.find { it.demandId.demandId == adUnit.demandId }
                    if (adapter == null) {
                        logInfo(TAG, "Auction ID: $auctionId | Adapter for Demand ID '${adUnit.demandId}' not found, skipping AdUnit #$index")
                        adUnitQueue.remove()
                        resultsCollector.add(
                            DemandResult.DemandFailed(
                                adUnit = adUnit,
                                demandStatus = DemandStatus.UnknownAdapter,
                                tokenInfo = tokenInfo
                            )
                        )
                        index++
                        continue
                    }

                    // Apply regulation
                    adapter.applyRegulation()

                    val adSource: AdSource<AdAuctionParams>? = adapter.getAdSources(demandAd.adType)
                    if (adSource == null) {
                        logInfo(TAG, "Auction ID: $auctionId | AdSource for Demand ID '${adUnit.demandId}' not found, skipping AdUnit #$index")
                        adUnitQueue.remove()
                        resultsCollector.add(
                            DemandResult.DemandFailed(
                                adUnit = adUnit,
                                demandStatus = DemandStatus.UnknownAdapter,
                                tokenInfo = tokenInfo
                            )
                        )
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

                    val requestAdUnitResult = requestAdUnit.invoke(
                        adSource = adSource,
                        adTypeParam = adTypeParam,
                        adUnit = adUnit,
                        priceFloor = pricefloor,
                        tokenInfo = tokenInfo
                    ).also {
                        resultsCollector.add(it)
                    }

                    logInfo(TAG, "Auction ID: $auctionId | AdUnit #$index request result -> ${requestAdUnitResult.demandStatus.code}")

                    val nextRequested = adUnitQueue.poll()
                    if (requestAdUnitResult.demandStatus == DemandStatus.Successful &&
                        !shouldRequestNext(demandResult = requestAdUnitResult, next = nextRequested)
                    ) {
                        logInfo(TAG, "Auction ID: $auctionId | Stopping requests after AdUnit #$index as filled eCPM is larger than the next adUnit")
                        adUnitQueue.forEach {
                            resultsCollector.add(
                                getBelowPriceFloorResult(
                                    adUnit = it,
                                    tokenInfo = tokenInfo
                                )
                            )
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
                finishWithStatus(
                    tokens = tokens,
                    resultsCollector = resultsCollector,
                    status = DemandStatus.FillTimeoutReached
                )
                logInfo(TAG, "Auction ID: $auctionId | Auction ended by timeout: ${auctionTimeout}ms")
            }
        }.onFailure {
            finishWithStatus(
                tokens = tokens,
                resultsCollector = resultsCollector,
                status = it.asBidonErrorOrUnspecified().asDemandStatus()
            )
            logError(TAG, "Auction ID: $auctionId | Error occurred during auction", it)
        }
    }

    private fun finishWithStatus(
        tokens: Map<String, TokenInfo>?,
        resultsCollector: ResultsCollector,
        status: DemandStatus
    ) {
        adUnitQueue.forEach {
            resultsCollector.add(
                DemandResult.DemandFailed(
                    adUnit = it,
                    demandStatus = status,
                    tokenInfo = tokens?.get(it.demandId)
                )
            )
        }
    }

    private fun getBelowPriceFloorResult(adUnit: AdUnit, tokenInfo: TokenInfo?): DemandResult {
        return when (adUnit.bidType) {
            BidType.RTB -> DemandResult.DemandFailed(
                adUnit = adUnit,
                demandStatus = DemandStatus.Lose,
                tokenInfo = tokenInfo,
            )

            BidType.CPM -> DemandResult.DemandFailed(
                adUnit = adUnit,
                demandStatus = DemandStatus.BelowPricefloor,
                tokenInfo = null
            )
        }
    }

    private fun shouldRequestNext(
        demandResult: DemandResult,
        next: AdUnit?
    ): Boolean {
        if (next == null) {
            return false
        }
        val currentEcpm = demandResult.adSource.getStats().ecpm
        val nextEcpm = next.pricefloor
        logInfo(TAG, "Loaded eCPM: $currentEcpm, next requested eCPM: $nextEcpm")
        return currentEcpm < nextEcpm
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