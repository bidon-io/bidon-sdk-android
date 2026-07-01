package org.bidon.unityads

import android.content.Context
import com.unity3d.ads.InitializationConfiguration
import com.unity3d.ads.InitializationListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsExperimental
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.adapter.SupportsTestMode
import org.bidon.sdk.adapter.impl.SupportsTestModeImpl
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.regulation.Regulation
import org.bidon.unityads.ext.adapterVersion
import org.bidon.unityads.ext.asBidonError
import org.bidon.unityads.ext.sdkVersion
import org.bidon.unityads.impl.UnityAdsBanner
import org.bidon.unityads.impl.UnityAdsBannerAuctionParams
import org.bidon.unityads.impl.UnityAdsFullscreenAuctionParams
import org.bidon.unityads.impl.UnityAdsInterstitial
import org.bidon.unityads.impl.UnityAdsRewarded
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Created by Bidon Team on 02/03/2023.
 */

internal val UnityAdsDemandId = DemandId("unityads")

/**
 * [Documentation](https://docs.unity.com/ads/en/manual/InitializingTheAndroidSDK)
 */
@Suppress("unused")
internal class UnityAdsAdapter :
    Adapter.Network,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    Initializable<UnityAdsParameters>,
    AdProvider.Banner<UnityAdsBannerAuctionParams>,
    AdProvider.Interstitial<UnityAdsFullscreenAuctionParams>,
    AdProvider.Rewarded<UnityAdsFullscreenAuctionParams> {

    private var context: Context? = null
    override val demandId: DemandId = UnityAdsDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    @OptIn(UnityAdsExperimental::class)
    override suspend fun init(context: Context, configParams: UnityAdsParameters) =
        suspendCoroutine { continuation ->
            this.context = context
            val config = InitializationConfiguration.Builder(configParams.unityGameId)
                .withTestMode(isTestMode)
                .build()

            val listener = InitializationListener { error ->
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    logError(TAG, "Error while initialization: ${error.message}", error.asBidonError())
                    continuation.resumeWithException(error.asBidonError())
                }
            }
            UnityAds.initialize(config, listener)
        }

    override fun parseConfigParam(json: String): UnityAdsParameters {
        return UnityAdsParameters(
            unityGameId = JSONObject(json).optString("game_id")
        )
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            UnityAds.userConsent = regulation.hasGdprConsent
        }
        if (regulation.ccpaApplies) {
            UnityAds.userOptOut = !regulation.hasCcpaConsent
        }
        if (regulation.coppaApplies) {
            UnityAds.nonBehavioral = true
        }
    }

    override fun interstitial(): AdSource.Interstitial<UnityAdsFullscreenAuctionParams> {
        return UnityAdsInterstitial()
    }

    override fun rewarded(): AdSource.Rewarded<UnityAdsFullscreenAuctionParams> {
        return UnityAdsRewarded()
    }

    override fun banner(): AdSource.Banner<UnityAdsBannerAuctionParams> {
        return UnityAdsBanner()
    }
}

private const val TAG = "UnityAdsAdapter"