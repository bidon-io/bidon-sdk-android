package org.bidon.sdk.ads.cache.denis.processors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import kotlin.coroutines.coroutineContext

/**
 * CPM waterfall processor with batch loading and early-stop optimization.
 *
 * Loads CPM adUnits in batches of [BATCH_SIZE] (2 at a time), sorted by pricefloor descending.
 * Within each batch, ad units load in parallel.
 * Stops after the first successful fill OR when the batch's lowest pricefloor
 * is less than or equal to the best cached RTB eCPM in ReadyToShowCache.
 *
 * Thread-safety: Coroutine-based with proper cancellation support.
 */
internal class CpmProcessor(
    private val adaptersSource: AdaptersSource,
    private val readyToShowCache: ReadyToShowCache,
    adTypeLabel: String = "",
) {
    private val TAG = "[DenisCache] CPM/$adTypeLabel"

    /**
     * Load CPM waterfall in batches of [BATCH_SIZE] with early-stop logic.
     *
     * Process:
     * 1. Sort adUnits by pricefloor descending (highest first)
     * 2. Chunk into batches of [BATCH_SIZE]
     * 3. For each batch:
     *    a. Check if batch's highest pricefloor <= best cached eCPM -> STOP
     *    b. Load all ads in the batch in parallel
     *    c. On first success -> fire callback -> STOP
     *
     * @param adUnits List of CPM ad units to load
     * @param params Common auction parameters
     * @return CpmWaterfallResult with success/failure counts
     */
    suspend fun loadWaterfall(
        adUnits: List<AdUnit>,
        params: AuctionParams,
    ): CpmWaterfallResult {
        // Sort by weighted score descending (fill rate × eCPM)
        val sortedAdUnits = WeightModel.sortByWeightedScore(adUnits)
        val batches = sortedAdUnits.chunked(BATCH_SIZE)

        var successCount = 0
        var failureCount = 0
        var firstSuccess: AuctionResult? = null
        val cacheEntries = mutableListOf<CacheEntry<AuctionResult>>()

        logInfo(
            TAG,
            "CPM waterfall (weighted): ${sortedAdUnits.joinToString { "${it.demandId}:w${WeightModel.getWeight(it.demandId)}" }}"
        )

        for ((batchIndex, batch) in batches.withIndex()) {
            // Check cancellation before each batch
            coroutineContext.ensureActive()

            // Early stop: if batch's highest pricefloor <= best cached eCPM, stop
            val bestCachedEcpm = readyToShowCache.getMaxEcpm()
            val batchHighestPricefloor = batch.first().pricefloor
            if (bestCachedEcpm > 0.0 && batchHighestPricefloor <= bestCachedEcpm) {
                logInfo(
                    TAG,
                    "CPM early stop: batch[$batchIndex] highest pricefloor=${"$%.2f".format(batchHighestPricefloor)} <= " +
                        "best cached eCPM=${"$%.2f".format(bestCachedEcpm)}, stopping waterfall"
                )
                break
            }

            // Load all ads in this batch in parallel
            val batchResults = supervisorScope {
                batch.map { adUnit ->
                    async {
                        adUnit to loadSingleAdUnit(
                            adUnit = adUnit,
                            params = params,
                        )
                    }
                }.map { deferred ->
                    runCatching { deferred.await() }.getOrElse { e ->
                        // Shouldn't happen with supervisorScope, but handle gracefully
                        null
                    }
                }
            }

            // Process batch results — pick best success (highest pricefloor)
            var batchBestSuccess: AuctionResult? = null
            var batchBestPricefloor = 0.0

            for ((entryIndex, entry) in batchResults.withIndex()) {
                if (entry == null) {
                    failureCount++
                    // Record no-fill for the corresponding ad unit in this batch
                    val failedAdUnit = batch.getOrNull(entryIndex)
                    if (failedAdUnit != null) {
                        WeightModel.recordNoFill(failedAdUnit.demandId)
                    }
                    continue
                }
                val (adUnit, result) = entry
                if (result.isSuccess) {
                    successCount++
                    val pair = result.getOrNull()
                    if (pair != null) {
                        val (auctionResult, cacheEntry) = pair
                        cacheEntries.add(cacheEntry)
                        if (adUnit.pricefloor > batchBestPricefloor) {
                            batchBestSuccess = auctionResult
                            batchBestPricefloor = adUnit.pricefloor
                        }
                    }
                } else {
                    failureCount++
                }
            }

            if (batchBestSuccess != null) {
                if (firstSuccess == null) {
                    firstSuccess = batchBestSuccess
                }
                logInfo(TAG, "CPM batch[$batchIndex] fill, stopping waterfall")
                break
            }
        }

        logInfo(TAG, "CPM complete: success=$successCount, failed=$failureCount")

        return CpmWaterfallResult(
            successCount = successCount,
            failureCount = failureCount,
            firstSuccess = firstSuccess,
            cacheEntries = cacheEntries,
        )
    }

    /**
     * Load single CPM ad unit.
     *
     * Process:
     * 1. Find adapter by demandId
     * 2. Create AdSource for ad type
     * 3. Apply auction parameters
     * 4. Load ad with timeout
     * 5. On success: Return CacheEntry (orchestrator handles cache insertion)
     * 6. On failure: Return failure (don't throw)
     * 7. Always destroy AdSource in finally block if not ready
     *
     * @param adUnit Ad unit to load
     * @param params Common auction parameters
     * @return Result with Pair of AuctionResult and CacheEntry on success, BidonError on failure
     */
    private suspend fun loadSingleAdUnit(
        adUnit: AdUnit,
        params: AuctionParams,
    ): Result<Pair<AuctionResult, CacheEntry<AuctionResult>>> {
        var adSource: AdSource<AdAuctionParams>? = null

        return try {
            // Find adapter by demandId
            val adapter = adaptersSource.adapters.find { it.demandId.demandId == adUnit.demandId }
                ?: run {
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.UnknownAdapter, tokenInfo = null
                    )
                    params.resultsCollector.add(failedResult)
                    return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }

            // Apply regulation
            adapter.applyRegulation()

            // Create AdSource
            adSource = AdSourceFactory.createAdSource(adapter, params.demandAd, params.adTypeParam, TAG)
                ?: run {
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    params.resultsCollector.add(failedResult)
                    return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }

            // Apply auction parameters
            AdSourceFactory.applyParams(
                adSource = adSource,
                auctionId = params.auctionId,
                auctionConfigurationId = params.auctionConfigurationId,
                auctionConfigurationUid = params.auctionConfigurationUid,
                externalWinNotificationsEnabled = params.externalWinNotificationsEnabled,
                demandAd = params.demandAd,
                pricefloor = params.pricefloor,
                adTypeParam = params.adTypeParam,
            )

            // Get auction params
            val adParams = adSource.getAuctionParam(
                org.bidon.sdk.adapter.AdAuctionParamSource(
                    activity = params.adTypeParam.activity,
                    pricefloor = params.pricefloor,
                    optBannerFormat = (params.adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (params.adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    adUnit = adUnit,
                )
            ).getOrNull()

            if (adParams == null) {
                adSource.destroy()
                val failedResult = AuctionResult.AuctionFailed(
                    adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                )
                params.resultsCollector.add(failedResult)
                return Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
            }

            // Mark fill started (sets adUnit in stats for getAd() calls)
            adSource.markFillStarted(adUnit, params.pricefloor)

            // Load ad with timeout (on Main thread for adapters like Admob)
            val adEvent = withTimeout(adUnit.timeout) {
                withContext(Dispatchers.Main) {
                    adSource.load(adParams)
                }
                adSource.adEvent.first { event ->
                    event is AdEvent.Fill || event is AdEvent.LoadFailed || event is AdEvent.Expired
                }
            }

            when (adEvent) {
                is AdEvent.Fill -> {
                    // Record fill for weight model
                    WeightModel.recordFill(adUnit.demandId)

                    // Update price to waterfall eCPM
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.Successful,
                        price = adUnit.pricefloor // For CPM use waterfall eCPM
                    )

                    val auctionResult: AuctionResult = AuctionResult.Network(adSource, RoundStatus.Successful)

                    // Report successful CPM result to ResultsCollector
                    params.resultsCollector.add(auctionResult)

                    // Create CacheEntry — orchestrator will sort and cache after both pipelines complete
                    val entry: CacheEntry<AuctionResult> = CacheEntry.create(
                        value = auctionResult,
                        ecpm = adUnit.pricefloor, // Use waterfall eCPM, not actual bid price
                        demandId = adUnit.demandId,
                        auctionId = params.auctionId,
                        uid = adUnit.uid
                    )
                    Result.success(auctionResult to entry)
                }
                is AdEvent.LoadFailed, is AdEvent.Expired -> {
                    // Record no-fill for weight model
                    WeightModel.recordNoFill(adUnit.demandId)

                    val error = when (adEvent) {
                        is AdEvent.LoadFailed -> adEvent.cause
                        is AdEvent.Expired -> BidonError.Expired(null)
                        else -> BidonError.NoFill(DemandId(adUnit.demandId))
                    }
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    params.resultsCollector.add(failedResult)
                    Result.failure(error)
                }
                else -> {
                    // Record no-fill for weight model
                    WeightModel.recordNoFill(adUnit.demandId)

                    logError(TAG, "Unexpected ad event: $adEvent", null)
                    val failedResult = AuctionResult.AuctionFailed(
                        adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
                    )
                    params.resultsCollector.add(failedResult)
                    Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NEVER catch CancellationException - always rethrow
            throw e
        } catch (e: Exception) {
            // Record no-fill for weight model
            WeightModel.recordNoFill(adUnit.demandId)

            val failedResult = AuctionResult.AuctionFailed(
                adUnit = adUnit, roundStatus = RoundStatus.NoFill, tokenInfo = null
            )
            params.resultsCollector.add(failedResult)
            Result.failure(BidonError.NoFill(DemandId(adUnit.demandId)))
        } finally {
            // Guaranteed cleanup even if cancelled (LIFE-06)
            // Only destroy if not successfully loaded into cache
            if (adSource != null && adSource.isAdReadyToShow != true) {
                adSource.safeDestroy(adUnit.demandId)
            }
        }
    }
}

/**
 * Result of CPM waterfall loading.
 *
 * @property successCount Number of successfully loaded ads
 * @property failureCount Number of failed loads
 * @property firstSuccess First successfully loaded ad (highest priority)
 * @property cacheEntries All successful cache entries for orchestrator to sort and insert
 */
internal data class CpmWaterfallResult(
    val successCount: Int,
    val failureCount: Int,
    val firstSuccess: AuctionResult?, // First successfully loaded ad
    val cacheEntries: List<CacheEntry<AuctionResult>>, // Entries for orchestrator to cache
)

private const val BATCH_SIZE = 2
