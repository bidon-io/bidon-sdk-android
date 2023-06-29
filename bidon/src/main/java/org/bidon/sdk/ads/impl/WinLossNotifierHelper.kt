package org.bidon.sdk.ads.impl

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.logs.logging.impl.logInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Aleksei Cherniaev on 27/06/2023.
 */
internal interface WinLossNotifierHelper {
    fun notifyWin(
        adSource: AdSource<*>?,
    )

    fun notifyLoss(
        winnerDemandId: String,
        winnerEcpm: Double,
        adSource: AdSource<*>?,
        onNotified: () -> Unit,
    )
}

internal class WinLossNotifierHelperImpl : WinLossNotifierHelper {
    private val wasNotified = AtomicBoolean(false)

    override fun notifyWin(
        adSource: AdSource<*>?,
    ) {
        logInfo(Tag, "Notify Win invoked")
        if (!wasNotified.getAndSet(true)) {
            if (adSource == null) {
                logInfo(Tag, "Notify Win skipped. No winner found.")
            } else {
                adSource.sendWin()
            }
        }
    }

    override fun notifyLoss(
        winnerDemandId: String,
        winnerEcpm: Double,
        adSource: AdSource<*>?,
        onNotified: () -> Unit,
    ) {
        logInfo(Tag, "Notify Loss invoked with Winner($winnerDemandId, $winnerEcpm)")
        if (!wasNotified.getAndSet(true)) {
            onNotified()
            if (adSource == null) {
                logInfo(Tag, "Notify Loss skipped. No winner found.")
            } else {
                adSource.sendLoss(
                    winnerDemandId = winnerDemandId,
                    winnerEcpm = winnerEcpm,
                )
            }
        }
    }
}

private const val Tag = "WinLossNotifier"