package org.bidon.admob

import android.content.Context
import com.google.android.gms.ads.MobileAds
import org.bidon.admob.ext.adapterVersion
import org.bidon.admob.ext.sdkVersion
import org.bidon.admob.impl.AdmobBannerImpl
import org.bidon.admob.impl.AdmobInterstitialImpl
import org.bidon.admob.impl.AdmobRewardedImpl
import org.bidon.admob.impl.GetTokenUseCase
import org.bidon.sdk.adapter.*
import org.bidon.sdk.auction.AdTypeParam
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

val AdmobDemandId = DemandId("admob")

@Suppress("unused")
internal class AdmobAdapter :
    Adapter.Bidding,
    Initializable<AdmobInitParameters>,
    AdProvider.Banner<AdmobBannerAuctionParams>,
    AdProvider.Rewarded<AdmobFullscreenAdAuctionParams>,
    AdProvider.Interstitial<AdmobFullscreenAdAuctionParams> {

    private var configParams: AdmobInitParameters? = null
    private var obtainToken: GetTokenUseCase? = null

    override val demandId = AdmobDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override suspend fun getToken(context: Context, adTypeParam: AdTypeParam): String? =
        obtainToken?.let { it(context, adTypeParam) }

    override fun isInitialized(context: Context): Boolean = isInitialized.get()

    override suspend fun init(context: Context, configParams: AdmobInitParameters): Unit = suspendCoroutine { continuation ->
        // Since Bidon is the mediator, no need to initialize Google Bidding's partner SDKs.
        // https://developers.google.com/android/reference/com/google/android/gms/ads/MobileAds?hl=en#disableMediationAdapterInitialization(android.content.Context)
        // MobileAds.disableMediationAdapterInitialization(context)
        this.configParams = configParams
        this.obtainToken = GetTokenUseCase(configParams)
        /**
         * Don't forget to disable automatic refresh for each AdUnit on the AdMob website.
         */
        MobileAds.initialize(context) {
            isInitialized.set(true)
            continuation.resume(Unit)
        }
    }

    override fun interstitial(): AdSource.Interstitial<AdmobFullscreenAdAuctionParams> {
        return AdmobInterstitialImpl(configParams)
    }

    override fun rewarded(): AdSource.Rewarded<AdmobFullscreenAdAuctionParams> {
        return AdmobRewardedImpl(configParams)
    }

    override fun banner(): AdSource.Banner<AdmobBannerAuctionParams> {
        return AdmobBannerImpl(configParams)
    }

    override fun parseConfigParam(json: String): AdmobInitParameters {
        val jsonObject = JSONObject(json)
        return AdmobInitParameters(
            requestAgent = jsonObject.optString("request_agent"),
            queryInfoType = jsonObject.optString("query_info_type")
        )
    }
}

private val isInitialized: AtomicBoolean = AtomicBoolean(false)
