package org.bidon.zmaticoo

import android.content.Context
import com.maticoo.sdk.InitConfiguration
import com.maticoo.sdk.core.InitCallback
import com.maticoo.sdk.core.MaticooAds
import com.zmaticoo.sdk.base.common.logging.ZmaticooLog
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.regulation.Regulation
import org.bidon.zmaticoo.ext.adapterVersion
import org.bidon.zmaticoo.ext.asBidonError
import org.bidon.zmaticoo.ext.sdkVersion
import org.bidon.zmaticoo.impl.GetTokenUseCase
import org.bidon.zmaticoo.impl.ParsePlacementsUseCase
import org.bidon.zmaticoo.impl.ZmaticooBannerAuctionParams
import org.bidon.zmaticoo.impl.ZmaticooBannerImpl
import org.bidon.zmaticoo.impl.ZmaticooFullscreenAuctionParams
import org.bidon.zmaticoo.impl.ZmaticooInterstitialImpl
import org.bidon.zmaticoo.impl.ZmaticooRewardedImpl
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Created by Bidon Team on 09/01/2026.
 */
internal val ZmaticooDemandId = DemandId("zmaticoo")

@Suppress("unused")
internal class ZmaticooAdapter :
    Adapter.Bidding,
    Initializable<ZmaticooParameters>,
    SupportsRegulation,
    AdProvider.Banner<ZmaticooBannerAuctionParams>,
    AdProvider.Interstitial<ZmaticooFullscreenAuctionParams>,
    AdProvider.Rewarded<ZmaticooFullscreenAuctionParams> {

    override val demandId: DemandId = ZmaticooDemandId
    override val adapterInfo =
        AdapterInfo(
            adapterVersion = adapterVersion,
            sdkVersion = sdkVersion
        )

    private var context: Context? = null
    private var placements: List<PlacementConfig> = emptyList()

    override suspend fun getToken(adTypeParam: AdTypeParam): String? {
        return GetTokenUseCase(adTypeParam, placements)
    }

    override suspend fun init(
        context: Context,
        configParams: ZmaticooParameters
    ) = suspendCoroutine { continuation ->
        this.context = context.applicationContext
        this.placements = configParams.placements

        val configuration =
            InitConfiguration
                .Builder()
                .appKey(configParams.appKey)
                .logEnable(BidonSdk.loggerLevel != Logger.Level.Off)
                .logLevel(
                    when (BidonSdk.loggerLevel) {
                        Logger.Level.Verbose -> ZmaticooLog.LogLevel.DEBUG
                        Logger.Level.Error -> ZmaticooLog.LogLevel.INFO
                        Logger.Level.Off -> ZmaticooLog.LogLevel.NONE
                    }
                )
                .build()

        MaticooAds.init(
            configuration,
            object : InitCallback {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onError(error: ComponentError?) {
                    val throwable = Exception("Zmaticoo init failed: ${error.asBidonError()}")
                    logError(TAG, throwable.message.orEmpty(), throwable)
                    continuation.resumeWithException(throwable)
                }
            }
        )
    }

    override fun parseConfigParam(json: String): ZmaticooParameters {
        val jsonObject = JSONObject(json)
        val appKey = jsonObject.getString("app_key")
        val placementsArray = jsonObject.getJSONArray("placement_ids")
        val placements = ParsePlacementsUseCase(placementsArray)

        return ZmaticooParameters(appKey, placements)
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            MaticooAds.setGDPRConsent(regulation.hasGdprConsent)
        }

        if (regulation.ccpaApplies) {
            context?.let {
                MaticooAds.setDoNotTrackStatus(it, !regulation.hasCcpaConsent)
            }
        }

        context?.let {
            MaticooAds.setCoppa(it, if (regulation.coppaApplies) 1 else 0)
        }
    }

    override fun banner(): AdSource.Banner<ZmaticooBannerAuctionParams> = ZmaticooBannerImpl()

    override fun interstitial(): AdSource.Interstitial<ZmaticooFullscreenAuctionParams> = ZmaticooInterstitialImpl()

    override fun rewarded(): AdSource.Rewarded<ZmaticooFullscreenAuctionParams> = ZmaticooRewardedImpl()
}

private const val TAG = "ZmaticooAdapter"
