package org.bidon.amazon

import android.content.Context
import com.amazon.device.ads.AdRegistration
import com.amazon.device.ads.MRAIDPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.amazon.ext.adapterVersion
import org.bidon.amazon.ext.sdkVersion
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsTestMode
import org.bidon.sdk.adapter.impl.SupportsTestModeImpl
import org.json.JSONObject
import kotlin.coroutines.resume


/**
 * Created by Aleksei Cherniaev on 27/09/2023.
 */
internal val AmazonDemandId = DemandId("amazon")

class AmazonAdapter : Adapter, Initializable<AmazonParameters>, SupportsTestMode by SupportsTestModeImpl() {
    override val demandId: DemandId = AmazonDemandId

    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override fun parseConfigParam(json: String): AmazonParameters {
        return AmazonParameters(
            appKey = JSONObject(json).getString("app_key")
        )
    }

    override suspend fun init(context: Context, configParams: AmazonParameters) = suspendCancellableCoroutine { continuation ->
        if (isTestMode) {
            AdRegistration.enableLogging(true)
            AdRegistration.enableTesting(true)
        }
        AdRegistration.getInstance(configParams.appKey, context)
        AdRegistration.setMRAIDSupportedVersions(arrayOf("1.0", "2.0", "3.0"))
        AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)
        continuation.resume(Unit)
    }
}