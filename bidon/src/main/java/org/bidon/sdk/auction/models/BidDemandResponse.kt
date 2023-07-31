package org.bidon.sdk.auction.models

import org.bidon.sdk.utils.serializer.JsonName

/**
 * Created by Aleksei Cherniaev on 26/07/2023.
 */
internal sealed interface BidDemandResponse {
    val payload: String
    val id: BiddingDemandName

    data class Mintegral(
        @field:JsonName("payload")
        override val payload: String,
        @field:JsonName("unit_id")
        val unitId: String,
        @field:JsonName("placement_id")
        val placementId: String
    ) : BidDemandResponse {
        override val id = BiddingDemandName.Mintegral
    }

    data class BidMachine(
        @field:JsonName("payload")
        override val payload: String,
    ) : BidDemandResponse {
        override val id = BiddingDemandName.BidMachine
        override fun toString(): String {
            return "BidMachine(payload=${payload.take(4)}..${payload.takeLast(4)})"
        }
    }

    data class Mobilefuse(
        @field:JsonName("payload")
        override val payload: String,
        @field:JsonName("placement_id")
        val placementId: String
    ) : BidDemandResponse {
        override val id = BiddingDemandName.Mobilefuse
    }

    data class Vungle(
        @field:JsonName("payload")
        override val payload: String,
        @field:JsonName("placement_id")
        val placementId: String
    ) : BidDemandResponse {
        override val id = BiddingDemandName.Vungle
    }

    data class BigoAds(
        @field:JsonName("payload")
        override val payload: String,
        @field:JsonName("slot_id")
        val slotId: String
    ) : BidDemandResponse {
        override val id = BiddingDemandName.BigoAds
    }

    data class Meta(
        @field:JsonName("payload")
        override val payload: String,
        @field:JsonName("placement_id")
        val placementId: String
    ) : BidDemandResponse {
        override val id = BiddingDemandName.Meta
    }
}