package org.bidon.sdk.ads.cache.impl.vladimir

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Observes a popped ad for [AdEvent.ShowFailed] events and automatically shows the backup ad.
 *
 * When show() fails on the primary ad, we automatically try to show the backup ad
 * from slot2 (now in slot1 after pop promoted it). The backup's events are forwarded
 * to the primary's event flow.
 *
 * ## Event flow to caller:
 * - If primary succeeds: Shown → Closed (normal flow)
 * - If primary fails, backup succeeds: ShowFailed → Shown → Closed
 * - If both fail: ShowFailed → ShowFailed
 */
internal class ShowFallbackHandler(
    private val scope: CoroutineScope,
    private val slots: CacheSlotManager,
) {
    var lastActivity: Activity? = null

    fun observe(result: AuctionResult) {
        val source = result.adSource
        val primaryDemandId = source.demandId.demandId
        var fallbackAttempted = false

        source.adEvent.onEach { event ->
            when {
                event is AdEvent.ShowFailed && !fallbackAttempted -> {
                    fallbackAttempted = true
                    logInfo(TAG, "ShowFailed on $primaryDemandId, trying backup...")

                    val backup = slots.pop()
                    if (backup != null) {
                        val backupSource = backup.adSource
                        val backupDemandId = backupSource.demandId.demandId
                        logInfo(TAG, "Found backup ad $backupDemandId, attempting show...")

                        backupSource.adEvent.onEach { backupEvent ->
                            logInfo(TAG, "Forwarding backup event $backupEvent from $backupDemandId to primary flow")
                            source.emitEvent(backupEvent)
                            if (backupEvent is AdEvent.Closed) {
                                lastActivity = null
                            }
                        }.launchIn(scope)

                        val activity = lastActivity
                        if (activity != null) {
                            when (backupSource) {
                                is AdSource.Interstitial<*> -> {
                                    logInfo(TAG, "Showing backup interstitial $backupDemandId")
                                    backupSource.show(activity)
                                }
                                is AdSource.Rewarded<*> -> {
                                    logInfo(TAG, "Showing backup rewarded $backupDemandId")
                                    backupSource.show(activity)
                                }
                                else -> {
                                    logInfo(TAG, "Backup $backupDemandId is not Interstitial or Rewarded, cannot show")
                                    lastActivity = null
                                }
                            }
                        } else {
                            logInfo(TAG, "No activity available for backup show, fallback failed")
                        }
                    } else {
                        logInfo(TAG, "No backup available for fallback")
                        lastActivity = null
                    }
                }
                event is AdEvent.Closed -> {
                    lastActivity = null
                }
            }
        }.launchIn(scope)
    }
}

private const val TAG = "AdCacheVladimir.ShowFallbackHandler"
