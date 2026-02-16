package org.bidon.sdk.auction

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal interface ExternalWinLossNotification {
    fun notifyWin()

    fun notifyLoss(
        winnerDemandId: String,
        winnerPrice: Double,

        /**
         * The publisher should be informed about failed loading in case of auction is cancelled.
         */
        onAuctionCancelled: () -> Unit,
        onNotified: () -> Unit,
    )
}