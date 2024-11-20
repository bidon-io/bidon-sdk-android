package org.bidon.sdk.auction.models

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandStatus

/**
 * Created by Bidon Team on 06/02/2023.
 */
sealed interface DemandResult {
    val adSource: AdSource<*>
    val demandStatus: DemandStatus

    class Network(
        override val adSource: AdSource<*>,
        override val demandStatus: DemandStatus,
    ) : DemandResult {
        override fun toString(): String {
            return "Network(adSource=$adSource, demandStatus=$demandStatus)"
        }
    }

    class Bidding(
        override val adSource: AdSource<*>,
        override val demandStatus: DemandStatus
    ) : DemandResult {
        override fun toString(): String {
            return "Bidding(adSource=$adSource, demandStatus=$demandStatus)"
        }
    }

    class DemandFailed(
        val adUnit: AdUnit,
        override val demandStatus: DemandStatus,
    ) : DemandResult {
        override val adSource: AdSource<*> get() = error("unexpected")
        override fun toString(): String {
            return "AuctionResult.${adUnit.getType()}(ecpm=${adUnit.pricefloor}, demandStatus=$demandStatus, ${adUnit.demandId})"
        }
    }
}

private fun AdUnit.getType() = if (bidType == BidType.RTB) "Bidding" else "Network"