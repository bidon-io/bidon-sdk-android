package org.bidon.dtexchange

import android.app.Activity
import android.util.Log
import com.fyber.inneractive.sdk.external.InneractiveAdManager
import com.fyber.inneractive.sdk.external.OnFyberMarketplaceInitializedListener.FyberInitStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.dtexchange.ext.adapterVersion
import org.bidon.dtexchange.ext.sdkVersion
import org.bidon.sdk.BidOnSdk
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.logs.logging.impl.logError
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val DataExchangeDemandId = DemandId("dt_exchange")

/**
 * Created by Aleksei Cherniaev on 28/02/2023.
 */
class DataExchangeAdapter : Adapter, Initializable<DataExchangeParameters> {
    override val demandId: DemandId = DataExchangeDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override suspend fun init(activity: Activity, configParams: DataExchangeParameters) =
        suspendCancellableCoroutine { continuation ->
            if (configParams.coppa) {
                InneractiveAdManager.currentAudienceIsAChild()
            }
            InneractiveAdManager.initialize(activity.applicationContext, configParams.appId) { initStatus ->
                when (initStatus) {
                    FyberInitStatus.SUCCESSFULLY -> {
                        continuation.resume(Unit)
                    }
                    FyberInitStatus.FAILED_NO_KITS_DETECTED,
                    FyberInitStatus.FAILED,
                    FyberInitStatus.INVALID_APP_ID, null -> {
                        val cause = Throwable("Adapter(${DataExchangeDemandId.demandId}) not initialized ($initStatus)")
                        logError(Tag, "Error while initialization", cause)
                        continuation.resumeWithException(cause)
                    }
                }
            }
            when(BidOnSdk.loggerLevel){
                Logger.Level.Verbose -> InneractiveAdManager.setLogLevel(Log.VERBOSE)
                Logger.Level.Error -> InneractiveAdManager.setLogLevel(Log.ERROR)
                Logger.Level.Off -> {
                    // do nothing
                }
            }
        }

    override fun parseConfigParam(json: String): DataExchangeParameters {
        return JSONObject(json).let {
            DataExchangeParameters(
                appId = requireNotNull(it.optString("app_id")),
                coppa = it.optBoolean("coppa", false)
            )
        }
    }
}

private const val Tag = "DataExchangeAdapter"