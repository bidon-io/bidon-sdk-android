package org.bidon.sdk.ads

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean

internal interface BidonAd {

    var isWaitingForInit: AtomicBoolean

    fun loadAd(activity: Activity, pricefloor: Double = BidonSdk.DefaultPricefloor)

    /**
     * Shows if ad is ready to show
     */
    fun isReady(): Boolean

    suspend fun initWaitAndContinueIfRequired(listener: AdListener?): Boolean {
        if (!BidonSdk.isInitialized() || BidonSdk.bidon.isInitializing) {
            if (BidonSdk.bidon.isInitFailed) {
                isWaitingForInit.set(true)
                logInfo(TAG, "Sdk was initialized with error")
                withContext(Dispatchers.Main) {
                    listener?.onAdLoadFailed(
                        auctionInfo = null,
                        cause = BidonError.SdkNotInitialized
                    )
                }
                return false
            }
            logInfo(
                TAG,
                "Sdk is not initialized. Ad will load automatically when initialization was complete"
            )
            BidonSdk.bidon.initAwaiter()
            if (BidonSdk.bidon.isInitFailed) {
                if (isWaitingForInit.compareAndSet(true, false)) {
                    logInfo(TAG, "Sdk was initialized with error")
                    withContext(Dispatchers.Main) {
                        listener?.onAdLoadFailed(
                            auctionInfo = null,
                            cause = BidonError.SdkNotInitialized
                        )
                    }
                }
                return false
            }
        }
        return true
    }
}