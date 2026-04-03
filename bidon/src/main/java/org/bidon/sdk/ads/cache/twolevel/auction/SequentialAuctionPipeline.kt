package org.bidon.sdk.ads.cache.twolevel.auction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.ext.ad
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.adapter.ext.getAdSources
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import org.bidon.sdk.utils.di.get
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Sequential auction pipeline for Two-Level Cache.
 *
 * Sequential auction pipeline that processes ad units one by one.
 * After each unit completes loading (fill or failure), [singleLoadCompletion] fires
 * for every fill and then the next unit is scheduled.
 *
 * Flow:
 *
 * 1. Collect RTB tokens from bidding adapters (like Denis CoordinationLayer).
 * 2. POST to /auction endpoint to receive the [adUnits] list.
 * 3. For EACH ad unit in order (sequential, not parallel):
 *    a. Find the adapter via [adaptersSource].
 *    b. Create an [org.bidon.sdk.adapter.AdSource] using [AdSourceFactory].
 *    c. Call [org.bidon.sdk.adapter.AdSource.load] and wait for [AdEvent.Fill] / [AdEvent.LoadFailed] / [AdEvent.Expired].
 *    d. On fill  → call [singleLoadCompletion] IMMEDIATELY with the [AuctionResult].
 *    e. On failure → log and continue to the next unit.
 * 4. After all units have been attempted → call [onComplete].
 *
 * The per-fill [singleLoadCompletion] callback is the critical difference from the standard
 * [org.bidon.sdk.auction.Auction] interface, which delivers ALL winners at once after the
 * whole waterfall finishes.
 *
 * No dependency on the existing [org.bidon.sdk.auction.Auction] interface.
 */
internal class SequentialAuctionPipeline(
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val auctionStat: AuctionStat,
    private val biddingConfig: BiddingConfig,
    private val adTypeLabel: String = "",
    private val auctionKey: String = "",
) {
    private val TAG = "[TwoLevelCache/$auctionKey] Sequential/$adTypeLabel"

    /**
     * Execute the sequential auction pipeline.
     *
     * Suspends until all ad units have been attempted.
     *
     * @param demandAd       Identifies the ad type and placement.
     * @param adTypeParam    Contains the activity, pricefloor, and format details.
     * @param singleLoadCompletion  Called immediately for every ad unit that fills.
     *                              Receives the [AuctionResult] for that unit.
     *                              Called in declaration order (sequential).
     * @param onComplete     Called once after all units are processed.
     *                       Receives [AuctionInfo] on success or null + [BidonError] on total failure.
     */
    suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        singleLoadCompletion: suspend (AuctionResult, Boolean) -> Unit,
        shouldContinueAuction: (ecpm: Double) -> Boolean,
        onComplete: suspend (AuctionInfo?, BidonError?) -> Unit,
    ) {
        val auctionId = UUID.randomUUID().toString()
        val resultsCollector: ResultsCollector = get()
        val pricefloor = adTypeParam.pricefloor

        try {
            resultsCollector.startRound(pricefloor)
            resultsCollector.serverBiddingStarted()

            auctionStat.markAuctionStarted(auctionId, adTypeParam)

            // Step 1: Collect RTB tokens from bidding adapters.
            logInfo(TAG, "Collecting tokens")
            val tokens = getTokens(
                adTypeParam = adTypeParam,
                adaptersSource = adaptersSource,
                tokenTimeout = biddingConfig.tokenTimeout,
            )

            // Step 2: Request auction configuration from /auction endpoint.
            logInfo(TAG, "Requesting auction auctionId=$auctionId pricefloor=$pricefloor")
            val auctionResponse = getAuctionRequest.request(
                adTypeParam = adTypeParam,
                auctionId = auctionId,
                demandAd = demandAd,
                adapters = adaptersSource.adapters.associate { it.demandId.demandId to it.adapterInfo },
                tokens = tokens,
            )

            auctionResponse.fold(
                onSuccess = { response ->
                    // Report server bidding result to ResultsCollector (deprecated but required
                    // for stats compatibility with the standard AuctionImpl flow).
                    resultsCollector.serverBiddingFinished(
                        response.adUnits?.filter { it.bidType == BidType.RTB }
                    )
                    resultsCollector.setNoBidInfo(response.noBids)
                    response.printWaterfall(demandAd.adType)

                    val adUnits = response.adUnits ?: emptyList()

                    if (adUnits.isEmpty()) {
                        logInfo(TAG, "No ad units in response — completing with NoFill")
                        val roundStat = proceedRoundResults(resultsCollector)
                        val auctionInfo = buildAuctionInfo(response, roundStat, auctionId)
                        auctionStat.sendAuctionStats(
                            auctionData = response,
                            roundStat = roundStat,
                            demandAd = demandAd,
                        )
                        resultsCollector.clear()
                        onComplete(auctionInfo, BidonError.NoFill(DemandId("auction")))
                        return
                    }

                    logInfo(TAG, "Sequential pipeline: ${adUnits.size} ad units to process")

                    // Step 3: Process ad units one by one (sequential flow).
                    var fillCount = 0
                    var processedCount = 0
                    try {
                        withTimeout(response.auctionTimeout) {
                            for ((index, adUnit) in adUnits.withIndex()) {
                                // Check coroutine cancellation between ad units.
                                coroutineContext.ensureActive()

                                // Pre-filter: can any cache accept this bid?
                                if (!shouldContinueAuction(adUnit.pricefloor)) {
                                    logInfo(TAG, "[$index/${adUnits.size}] Stop: no cache can accept ecpm=${adUnit.pricefloor}")
                                    // Mark remaining ad units (matches V1 ExecuteAuctionUseCaseImpl)
                                    for (remaining in adUnits.subList(index, adUnits.size)) {
                                        val status = when (remaining.bidType) {
                                            BidType.RTB -> RoundStatus.Lose
                                            else -> RoundStatus.BelowPricefloor
                                        }
                                        resultsCollector.add(
                                            AuctionResult.AuctionFailed(
                                                adUnit = remaining,
                                                roundStatus = status,
                                                tokenInfo = tokens[remaining.demandId],
                                            )
                                        )
                                    }
                                    processedCount = adUnits.size
                                    break
                                }

                                logInfo(TAG, "[$index/${adUnits.size}] Loading ${adUnit.demandId} pricefloor=${adUnit.pricefloor}")

                                val result = loadSingleAdUnit(
                                    adUnit = adUnit,
                                    demandAd = demandAd,
                                    adTypeParam = adTypeParam,
                                    auctionId = auctionId,
                                    auctionConfigurationId = response.auctionConfigurationId ?: 0L,
                                    auctionConfigurationUid = response.auctionConfigurationUid ?: "",
                                    externalWinNotificationsEnabled = response.externalWinNotificationsEnabled,
                                    pricefloor = pricefloor,
                                    resultsCollector = resultsCollector,
                                    tokens = tokens,
                                )

                                processedCount = index + 1

                                if (result != null) {
                                    fillCount++
                                    // singleLoadCompletion fires immediately after a demand fill.
                                    logInfo(TAG, "[$index/${adUnits.size}] Fill: ${adUnit.demandId}")
                                    singleLoadCompletion(result, response.externalWinNotificationsEnabled)
                                } else {
                                    logInfo(TAG, "[$index/${adUnits.size}] No fill: ${adUnit.demandId}")
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        logInfo(TAG, "Auction timed out after ${response.auctionTimeout}ms, using $fillCount fills so far")
                        // Mark unattempted ad units as FillTimeoutReached (matches ExecuteAuctionUseCaseImpl)
                        for (i in processedCount until adUnits.size) {
                            val remaining = adUnits[i]
                            resultsCollector.add(
                                AuctionResult.AuctionFailed(
                                    adUnit = remaining,
                                    roundStatus = RoundStatus.FillTimeoutReached,
                                    tokenInfo = tokens[remaining.demandId],
                                )
                            )
                        }
                    }

                    logInfo(TAG, "Sequential pipeline complete: $fillCount fills of ${adUnits.size} units")

                    // Collect round results and build AuctionInfo after all fills are done.
                    val roundStat = proceedRoundResults(resultsCollector)
                    val auctionInfo = buildAuctionInfo(response, roundStat, auctionId)

                    auctionStat.sendAuctionStats(
                        auctionData = response,
                        roundStat = roundStat,
                        demandAd = demandAd,
                    )
                    resultsCollector.clear()

                    if (fillCount > 0) {
                        onComplete(auctionInfo, null)
                    } else {
                        onComplete(auctionInfo, BidonError.NoFill(DemandId("auction")))
                    }
                },
                onFailure = { error ->
                    logInfo(TAG, "Auction request failed: ${error.message}")
                    val roundStat = proceedRoundResults(resultsCollector)
                    auctionStat.sendAuctionStats(
                        auctionData = org.bidon.sdk.auction.models.AuctionResponse(
                            auctionId = auctionId,
                            adUnits = null,
                            noBids = null,
                            pricefloor = pricefloor,
                            auctionTimeout = 0,
                            auctionConfigurationId = null,
                            auctionConfigurationUid = null,
                            externalWinNotificationsEnabled = false,
                        ),
                        roundStat = roundStat,
                        demandAd = demandAd,
                    )
                    resultsCollector.clear()
                    onComplete(null, BidonError.InternalServerSdkError(error.message ?: "Auction request failed"))
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            auctionStat.markAuctionCanceled()
            throw e
        } catch (e: Exception) {
            logError(TAG, "Unexpected error during sequential pipeline", e)
            onComplete(null, BidonError.InternalServerSdkError(e.message ?: "Unexpected error"))
        }
    }

    // -------------------------------------------------------------------------
    // Single ad unit loading
    // -------------------------------------------------------------------------

    /**
     * Attempt to load a single ad unit.
     *
     * @return [AuctionResult] on fill, null on any failure.
     */
    private suspend fun loadSingleAdUnit(
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
        resultsCollector: ResultsCollector,
        tokens: Map<String, TokenInfo>,
    ): AuctionResult? {
        val demandId = adUnit.demandId
        val tokenInfo = tokens[demandId]

        // Find adapter by demandId.
        val adapter = adaptersSource.adapters.find { it.demandId.demandId == demandId }
        if (adapter == null) {
            logInfo(TAG, "Adapter not found: demandId=$demandId")
            resultsCollector.add(
                AuctionResult.AuctionFailed(
                    adUnit = adUnit,
                    roundStatus = RoundStatus.UnknownAdapter,
                    tokenInfo = tokenInfo,
                )
            )
            return null
        }

        adapter.applyRegulation()

        val adSource = adapter.getAdSources(demandAd.adType, TAG)
        if (adSource == null) {
            logInfo(TAG, "AdSource creation failed: demandId=$demandId")
            resultsCollector.add(
                AuctionResult.AuctionFailed(
                    adUnit = adUnit,
                    roundStatus = RoundStatus.NoFill,
                    tokenInfo = tokenInfo,
                )
            )
            return null
        }

        var loadSuccess = false

        try {
            AdSourceFactory.applyParams(
                adSource = adSource,
                auctionId = auctionId,
                auctionConfigurationId = auctionConfigurationId,
                auctionConfigurationUid = auctionConfigurationUid,
                externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                demandAd = demandAd,
                pricefloor = pricefloor,
                adTypeParam = adTypeParam,
            )

            if (adUnit.bidType == BidType.RTB) {
                tokens[demandId]?.let { adSource.setTokenInfo(it) }
            }

            val adParams = adSource.getAuctionParam(
                AdAuctionParamSource(
                    activity = adTypeParam.activity,
                    pricefloor = pricefloor,
                    optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    adUnit = adUnit,
                )
            ).getOrNull()

            if (adParams == null) {
                logInfo(TAG, "Failed to build auction params: demandId=$demandId")
                resultsCollector.add(
                    AuctionResult.AuctionFailed(
                        adUnit = adUnit,
                        roundStatus = RoundStatus.NoFill,
                        tokenInfo = tokenInfo,
                    )
                )
                adSource.destroy()
                return null
            }

            adSource.markFillStarted(adUnit, adUnit.pricefloor)

            // Load on Main thread (required by some adapters, e.g. AdMob).
            // Use withTimeoutOrNull so per-unit timeout returns null instead of throwing
            // TimeoutCancellationException. This prevents the inner per-unit timeout from
            // propagating to the outer auction timeout catch and killing the entire pipeline.
            val adEvent = withTimeoutOrNull(adUnit.timeout) {
                withContext(Dispatchers.Main) {
                    adSource.load(adParams)
                }
                adSource.adEvent.first { event ->
                    event is AdEvent.Fill || event is AdEvent.LoadFailed || event is AdEvent.Expired
                }
            }

            if (adEvent == null) {
                logInfo(TAG, "Per-unit fill timeout reached: demandId=$demandId (${adUnit.timeout}ms)")
                adSource.markFillFinished(
                    roundStatus = RoundStatus.FillTimeoutReached,
                    price = null,
                )
                resultsCollector.add(
                    AuctionResult.AuctionFailed(
                        adUnit = adUnit,
                        roundStatus = RoundStatus.FillTimeoutReached,
                        tokenInfo = tokenInfo,
                    )
                )
                return null
            }

            return when (adEvent) {
                is AdEvent.Fill -> {
                    val loadedPrice = adSource.ad?.price ?: adUnit.pricefloor
                    val isBidding = adUnit.bidType == BidType.RTB

                    // Below-pricefloor check (matches RequestAdUnitUseCaseImpl)
                    val requestStatus = when {
                        loadedPrice >= pricefloor -> RoundStatus.Successful
                        isBidding -> RoundStatus.Lose
                        else -> RoundStatus.BelowPricefloor
                    }

                    adSource.markFillFinished(requestStatus, loadedPrice)

                    if (requestStatus != RoundStatus.Successful) {
                        logInfo(TAG, "BelowPricefloor: demandId=$demandId loaded=$loadedPrice floor=$pricefloor")
                        resultsCollector.add(
                            AuctionResult.AuctionFailed(
                                adUnit = adUnit,
                                roundStatus = requestStatus,
                                tokenInfo = tokenInfo,
                            )
                        )
                        null
                    } else {
                        loadSuccess = true

                        val auctionResult: AuctionResult = if (isBidding) {
                            AuctionResult.Bidding(adSource, RoundStatus.Successful)
                        } else {
                            AuctionResult.Network(adSource, RoundStatus.Successful)
                        }

                        resultsCollector.add(auctionResult)
                        auctionResult
                    }
                }
                is AdEvent.LoadFailed -> {
                    val error = adEvent.cause
                    logInfo(TAG, "LoadFailed: demandId=$demandId error=$error")
                    val roundStatus = (error as Throwable).asRoundStatus()
                    adSource.markFillFinished(roundStatus, null)
                    resultsCollector.add(
                        AuctionResult.AuctionFailed(
                            adUnit = adUnit,
                            roundStatus = roundStatus,
                            tokenInfo = tokenInfo,
                        )
                    )
                    null
                }
                is AdEvent.Expired -> {
                    logInfo(TAG, "Expired: demandId=$demandId")
                    adSource.markFillFinished(RoundStatus.NoFill, null)
                    resultsCollector.add(
                        AuctionResult.AuctionFailed(
                            adUnit = adUnit,
                            roundStatus = RoundStatus.NoFill,
                            tokenInfo = tokenInfo,
                        )
                    )
                    null
                }
                else -> {
                    logError(TAG, "Unexpected ad event: $adEvent", null)
                    adSource.markFillFinished(RoundStatus.NoFill, null)
                    resultsCollector.add(
                        AuctionResult.AuctionFailed(
                            adUnit = adUnit,
                            roundStatus = RoundStatus.NoFill,
                            tokenInfo = tokenInfo,
                        )
                    )
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Rethrow so the outer withTimeout / coroutineContext cancellation propagates.
            throw e
        } catch (e: Exception) {
            logInfo(TAG, "Exception loading demandId=$demandId: ${e.message}")
            adSource.markFillFinished(RoundStatus.NoFill, null)
            resultsCollector.add(
                AuctionResult.AuctionFailed(
                    adUnit = adUnit,
                    roundStatus = RoundStatus.NoFill,
                    tokenInfo = tokenInfo,
                )
            )
            null
        } finally {
            // Guaranteed cleanup: destroy the ad source if it did not fill successfully.
            if (!loadSuccess) {
                adSource.safeDestroy(demandId)
            }
        }

        // Unreachable: all paths above either return or throw.
        return null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun proceedRoundResults(resultsCollector: ResultsCollector): RoundStat? {
        return (resultsCollector.getRoundResults() as? RoundResult.Results)?.let {
            auctionStat.addRoundResults(it)
        }
    }

    private fun buildAuctionInfo(
        response: org.bidon.sdk.auction.models.AuctionResponse,
        roundStat: RoundStat?,
        auctionId: String,
    ): AuctionInfo {
        return AuctionInfo(
            auctionId = auctionId,
            auctionConfigurationId = response.auctionConfigurationId,
            auctionConfigurationUid = response.auctionConfigurationUid,
            auctionTimeout = response.auctionTimeout,
            auctionPricefloor = response.pricefloor,
            noBids = roundStat?.noBids?.map { it.toAuctionNoBidInfo() },
            adUnits = roundStat?.demands?.map { it.toAuctionInfo() },
        )
    }
}
