package org.bidon.sdk.stats

interface WinLossNotifier {
    @Deprecated("With ad caching logic, it works incorrectly")
    fun notifyLoss(winnerDemandId: String, winnerEcpm: Double)

    @Deprecated("With ad caching logic, it works incorrectly")
    fun notifyWin()
}
