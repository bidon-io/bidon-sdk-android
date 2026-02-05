package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Result of splitting waterfall into RTB and CPM groups.
 */
internal data class SplitWaterfall(
    val rtbAdUnits: List<AdUnit>,
    val cpmAdUnits: List<AdUnit>
)

/**
 * Splits auction waterfall into RTB and CPM groups based on adapter type.
 *
 * Decision logic (from 03-CONTEXT.md):
 * - Check if adapter implements Adapter.Bidding interface
 * - If Adapter.Bidding: treat as RTB only (ignore CPM config even if present)
 * - Otherwise: treat as CPM
 *
 * This matches GetTokensUseCaseImpl pattern of using filterIsInstance<Adapter.Bidding>.
 */
internal object WaterfallSplitter {
    private const val TAG = "WaterfallSplitter"

    /**
     * Split AdUnits into RTB and CPM groups.
     *
     * @param adUnits List of ad units from auction response
     * @param adaptersSource Source of available adapters
     * @return SplitWaterfall with separate RTB and CPM lists
     */
    fun split(adUnits: List<AdUnit>, adaptersSource: AdaptersSource): SplitWaterfall {
        // Build set of bidding adapter demand IDs
        // Pattern matches GetTokensUseCaseImpl: filterIsInstance<Adapter.Bidding>()
        val biddingDemandIds = adaptersSource.adapters
            .filterIsInstance<Adapter.Bidding>()
            .map { it.demandId.demandId }
            .toSet()

        // Partition AdUnits based on adapter type
        val rtbAdUnits = mutableListOf<AdUnit>()
        val cpmAdUnits = mutableListOf<AdUnit>()

        adUnits.forEach { adUnit ->
            if (adUnit.demandId in biddingDemandIds) {
                rtbAdUnits.add(adUnit)
            } else {
                cpmAdUnits.add(adUnit)
            }
        }

        logInfo(TAG, "Waterfall split: ${rtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM from ${adUnits.size} total")

        return SplitWaterfall(
            rtbAdUnits = rtbAdUnits,
            cpmAdUnits = cpmAdUnits
        )
    }
}
