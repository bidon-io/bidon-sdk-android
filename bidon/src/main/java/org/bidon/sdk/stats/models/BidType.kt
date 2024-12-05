package org.bidon.sdk.stats.models

/**
 * Created by Bidon Team on 11/09/2023.
 */
enum class BidType(val code: String) {
    /**
     * Real time bidding
     */
    RTB("RTB"),

    /**
     * Pseudo-bidding via eCPM
     */
    CPM("CPM"),
}