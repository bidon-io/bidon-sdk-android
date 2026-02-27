package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus

internal class WinLossNotifier(
    private val tag: String,
) {
    fun notify(
        finalResults: List<AuctionResult>,
        externalWinNotificationsEnabled: Boolean,
    ) {
        val winners =
            finalResults
                .filter { it.roundStatus == RoundStatus.Successful }

        winners.forEach {
            val adSource = it.adSource
            // For internal statistics
            adSource.markWin()
            // For AdNetworks - notify winner only if external notifications are disabled
            // Bidding demands should not be notified (server notifies them)
            if (!externalWinNotificationsEnabled) {
                if (it !is AuctionResult.Bidding && adSource is WinLossNotifiable) {
                    adSource.notifyWin()
                    logInfo(
                        tag,
                        "Notified win to adapter: ${adSource.demandId} (external_win_notifications=false)"
                    )
                } else if (it is AuctionResult.Bidding) {
                    logInfo(
                        tag,
                        "Skipped win notification for bidding demand: ${adSource.demandId}"
                    )
                }
            } else {
                logInfo(
                    tag,
                    "Skipped win notification to adapter: ${adSource.demandId} (external_win_notifications=true, will be notified externally)"
                )
            }
        }

        val winnerAdSource = winners.firstOrNull()?.adSource ?: return

        // Notify all losers regardless of external_win_notifications flag
        (finalResults - winners.toSet())
            .filterIsInstance<AuctionResult.Network>()
            .forEach {
                val loserAdSource = it.adSource
                // Bidding demands should not be notified.
                // All losers should be notified immediately regardless of external_win_notifications
                if (loserAdSource is WinLossNotifiable) {
                    loserAdSource.notifyLoss(
                        winnerAdSource.demandId.demandId,
                        winnerAdSource.getStats().price
                    )
                    logInfo(tag, "Notified loss to ${loserAdSource.demandId} (winner=${winnerAdSource.demandId.demandId}, price=${winnerAdSource.getStats().price})")
                }
            }
    }
}
