package org.bidon.sdk.ads.cache.denis.orchestration

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Parallel auction orchestrator for RTB + CPM execution.
 *
 * Executes RTB and CPM branches in parallel using async/supervisorScope:
 * - RTB failure doesn't cancel CPM
 * - CPM failure doesn't cancel RTB
 * - Both branches always run to completion
 * - Callback fires when cache transitions from empty to non-empty
 *
 * Cancellation support:
 * - cancelIfSameAuction() only cancels auctions with matching auctionId
 * - Prevents cancelling unrelated auctions when showing cached ad
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 */
internal class ParallelAuctionOrchestrator(
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor,
    private val callbackCoordinator: CallbackCoordinator,
) {
    private var currentAuctionId: String? = null

    /**
     * Execute parallel auction (RTB + CPM).
     *
     * Process:
     * 1. Record cache state before auction
     * 2. Launch RTB and CPM branches in parallel (async + supervisorScope)
     * 3. Wait for both to complete
     * 4. Fire callback based on results and cache state
     *
     * @param rtbPayloadsAvailable Whether RtbPayloadCache has entries
     * @param cpmAdUnits CPM ad units to load
     * @param adTypeParam Ad type parameters (Interstitial/Rewarded/Banner)
     * @param demandAd Demand ad configuration
     * @param auctionId Auction identifier for tracking
     * @param auctionConfigurationId Auction configuration ID
     * @param auctionConfigurationUid Auction configuration UID
     * @param externalWinNotificationsEnabled Win notification flag
     * @param pricefloor Minimum acceptable price
     * @param auctionInfo Auction information for callback
     */
    suspend fun executeParallelAuction(
        rtbPayloadsAvailable: Boolean,
        cpmAdUnits: List<AdUnit>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        pricefloor: Double,
        auctionInfo: AuctionInfo,
    ) {
        // Record current auction for cancellation support
        currentAuctionId = auctionId

        // Record cache state BEFORE auction
        val cacheWasEmpty = ReadyToShowCache.isEmpty()
        callbackCoordinator.setCacheEmptyAtStart(cacheWasEmpty)

        logInfo(
            TAG,
            "Starting parallel auction: auctionId=$auctionId, " +
                "rtbAvailable=$rtbPayloadsAvailable, cpmAdUnits=${cpmAdUnits.size}, " +
                "cacheWasEmpty=$cacheWasEmpty"
        )

        coroutineScope {
            // RTB branch (independent failure domain)
            val rtbDeferred = async {
                if (!rtbPayloadsAvailable) {
                    logInfo(TAG, "Skipping RTB branch: no cached payloads")
                    return@async null
                }
                // supervisorScope isolates RTB failures (doesn't cancel CPM)
                supervisorScope {
                    logInfo(TAG, "RTB branch starting")
                    val result = rtbProcessor.loadBestPayload(
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = auctionConfigurationId,
                        auctionConfigurationUid = auctionConfigurationUid,
                        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                        pricefloor = pricefloor,
                    )
                    logInfo(TAG, "RTB branch completed: success=${result.isSuccess}")
                    result
                }
            }

            // CPM branch (independent failure domain)
            val cpmDeferred = async {
                if (cpmAdUnits.isEmpty()) {
                    logInfo(TAG, "Skipping CPM branch: no adUnits")
                    return@async null
                }
                // supervisorScope isolates CPM failures (doesn't cancel RTB)
                supervisorScope {
                    logInfo(TAG, "CPM branch starting")
                    val result = cpmProcessor.loadWaterfall(
                        adUnits = cpmAdUnits,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        auctionId = auctionId,
                        auctionConfigurationId = auctionConfigurationId,
                        auctionConfigurationUid = auctionConfigurationUid,
                        externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                        pricefloor = pricefloor,
                    )
                    logInfo(
                        TAG,
                        "CPM branch completed: success=${result.successCount}, " +
                            "failure=${result.failureCount}"
                    )
                    result
                }
            }

            // Wait for both branches to complete
            val rtbResult = rtbDeferred.await()
            val cpmResult = cpmDeferred.await()

            // Check if ANY success occurred
            val rtbSuccess = rtbResult?.isSuccess == true
            val cpmSuccess = cpmResult?.firstSuccess != null

            logInfo(
                TAG,
                "Parallel auction completed: rtbSuccess=$rtbSuccess, cpmSuccess=$cpmSuccess"
            )

            // Check cache state and notify appropriately
            checkAndNotifyCallback(
                rtbSuccess = rtbSuccess,
                cpmSuccess = cpmSuccess,
                rtbResult = rtbResult,
                cpmResult = cpmResult,
                auctionInfo = auctionInfo,
                cacheWasEmpty = cacheWasEmpty
            )
        }
    }

    /**
     * Check cache state and fire appropriate callback.
     *
     * Logic:
     * - If cache transitioned from empty to non-empty: fire onAdLoaded with best ad
     * - If both branches failed AND cache still empty: fire onAdLoadFailed
     * - Otherwise: no callback (cache was already non-empty, warm start scenario)
     */
    private fun checkAndNotifyCallback(
        rtbSuccess: Boolean,
        cpmSuccess: Boolean,
        rtbResult: Result<org.bidon.sdk.auction.models.AuctionResult>?,
        cpmResult: org.bidon.sdk.ads.cache.denis.processors.CpmWaterfallResult?,
        auctionInfo: AuctionInfo,
        cacheWasEmpty: Boolean
    ) {
        // Check if ANY success occurred
        if (rtbSuccess || cpmSuccess) {
            // At least one branch succeeded
            // Check if cache transitioned from empty to non-empty
            if (cacheWasEmpty && !ReadyToShowCache.isEmpty()) {
                // Cache populated - fire callback with best ad
                ReadyToShowCache.getBest()?.let { entry ->
                    logInfo(
                        TAG,
                        "Cache populated (was empty): firing onAdLoaded with " +
                            "demandId=${entry.demandId}, ecpm=${entry.ecpm}"
                    )
                    callbackCoordinator.notifySuccess(entry.value, auctionInfo)
                }
            } else if (!cacheWasEmpty) {
                logInfo(TAG, "Cache was already non-empty: no callback needed (warm start)")
            } else {
                logInfo(TAG, "Success but cache still empty: unexpected state")
            }
        } else {
            // Both branches failed
            logInfo(TAG, "Both RTB and CPM branches failed")
            // CallbackCoordinator checks if cache was empty before firing failure
            callbackCoordinator.notifyFailure(auctionInfo, BidonError.NoFill(DemandId("auction")))
        }
    }

    /**
     * Check if auction ID matches current auction.
     *
     * Used when showing an ad from cache - caller can decide whether to
     * cancel the running auction based on auctionId match.
     *
     * @param auctionId Auction ID of ad being shown
     * @return true if auctionId matches current auction
     */
    fun isCurrentAuction(auctionId: String): Boolean {
        return currentAuctionId == auctionId
    }
}

private const val TAG = "ParallelAuctionOrchestrator"
