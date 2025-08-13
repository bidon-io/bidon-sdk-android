package org.bidon.sdk.adapter.ext

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector

internal val AdSource<*>.ad get() = (this as StatisticsCollector).getAd()

private const val TAG = "AdSourceExt"

/**
 * Sends loss notification to both server and adapter if conditions are met.
 * Respects externalWinNotificationsEnabled flag and prevents duplicate notifications.
 */
internal fun AdSource<*>.notifyExternalLoss(winnerDemandId: String, winnerPrice: Double) {
    val statisticsCollector = this as StatisticsCollector
    if (statisticsCollector.canSendWinLoseNotifications()) {
        statisticsCollector.markWinLoseNotificationsSent()

        logInfo(TAG, "Sending loss notification to server and adapter: ${statisticsCollector.demandId}")
        statisticsCollector.sendLoss(winnerDemandId, winnerPrice)
        (this as WinLossNotifiable).notifyLoss(winnerDemandId, winnerPrice)
    } else {
        logInfo(TAG, "Not sending loss notification to server and adapter: ${statisticsCollector.demandId}")
    }
}

/**
 * Sends win notification to both server and adapter if conditions are met.
 * Respects externalWinNotificationsEnabled flag and prevents duplicate notifications.
 */
internal fun AdSource<*>.notifyExternalWin() {
    val statisticsCollector = this as StatisticsCollector
    if (statisticsCollector.canSendWinLoseNotifications()) {
        statisticsCollector.markWinLoseNotificationsSent()

        logInfo(TAG, "Sending win notification to server and adapter: ${statisticsCollector.demandId}")
        statisticsCollector.sendWin()
        (this as WinLossNotifiable).notifyWin()
    } else {
        logInfo(TAG, "Not sending win notification to server and adapter: ${statisticsCollector.demandId}")
    }
}
