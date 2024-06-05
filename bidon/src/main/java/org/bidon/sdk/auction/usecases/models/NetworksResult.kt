package org.bidon.sdk.auction.usecases.models

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

/**
 * Created by Aleksei Cherniaev on 01/06/2023.
 */
internal class NetworksResult(
    val results: List<AuctionResult>,
    /**
     * Remaining LineItems, excluded consumed ones
     */
    val remainingAdUnits: List<AdUnit>
)
