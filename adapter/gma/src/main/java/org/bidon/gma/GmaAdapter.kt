package org.bidon.gma

import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bidon.gma.ext.adapterVersion
import org.bidon.gma.ext.sdkVersion
import org.bidon.gma.impl.GmaBannerImpl
import org.bidon.gma.impl.GmaInterstitialImpl
import org.bidon.gma.impl.GmaRewardedImpl
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal val GmaDemandId = DemandId("gma")

/**
 * [Google Mobile Ads Next-Gen SDK](https://developers.google.com/admob/android/next-gen/quick-start)
 */
@Suppress("unused")
internal class GmaAdapter :
    Adapter.Network,
    Initializable<GmaInitParameters>,
    AdProvider.Banner<GmaBannerAuctionParams>,
    AdProvider.Rewarded<GmaFullscreenAdAuctionParams>,
    AdProvider.Interstitial<GmaFullscreenAdAuctionParams> {

    override val demandId = GmaDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override suspend fun init(context: Context, configParams: GmaInitParameters): Unit =
        withContext(Dispatchers.IO) {
            suspendCoroutine { continuation ->
                val config = InitializationConfig.Builder(configParams.appId ?: "").build()
                MobileAds.initialize(context, config) {
                    continuation.resume(Unit)
                }
            }
        }

    override fun interstitial(): AdSource.Interstitial<GmaFullscreenAdAuctionParams> {
        return GmaInterstitialImpl()
    }

    override fun rewarded(): AdSource.Rewarded<GmaFullscreenAdAuctionParams> {
        return GmaRewardedImpl()
    }

    override fun banner(): AdSource.Banner<GmaBannerAuctionParams> {
        return GmaBannerImpl()
    }

    override fun parseConfigParam(json: String): GmaInitParameters {
        val jsonObject = JSONObject(json)
        return GmaInitParameters(
            appId = jsonObject.optString("app_id").takeIf { it.isNotEmpty() }
        )
    }
}
