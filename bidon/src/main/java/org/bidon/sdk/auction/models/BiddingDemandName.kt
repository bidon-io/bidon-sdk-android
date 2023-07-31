package org.bidon.sdk.auction.models

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal enum class BiddingDemandName(val code: String) {
    Mintegral("mintegral"),
    BidMachine("bidmachine"),
    Mobilefuse("mobilefuse"),
    Vungle("vungle"),
    BigoAds("bigoads"),
    Meta("meta");

    companion object {
        fun getOrNull(key: String) = values().firstOrNull { it.code == key }
    }
}