package org.bidon.zmaticoo

import android.content.Context
import com.maticoo.sdk.InitConfiguration
import com.maticoo.sdk.core.InitCallback
import com.maticoo.sdk.core.MaticooAds
import com.zmaticoo.sdk.base.common.ZMaticooAds
import com.zmaticoo.sdk.flow.model.ComponentError
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.adapter.SupportsTestMode
import org.bidon.sdk.adapter.impl.SupportsTestModeImpl
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.zmaticoo.ext.adapterVersion
import org.bidon.zmaticoo.ext.asBidonError
import org.bidon.zmaticoo.ext.sdkVersion
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
 * Created by Vladimir Khrolovich on 09/01/2026.
 */
internal val ZmaticooDemandId = DemandId("zmaticoo")

@Suppress("unused")
internal class ZmaticooAdapter :
    Adapter.Bidding,
    Initializable<ZmaticooParameters>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
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
        val tokensMap = mutableMapOf<String, JSONObject>()

        for (placement in placements) {
            val timestamp = System.currentTimeMillis()
            val token = ZMaticooAds.getBiddingToken(placement.placementId, timestamp)
            if (!token.isNullOrEmpty()) {
                tokensMap[placement.placementId] =
                    JSONObject().apply {
                        put("token", token)
                        put("timestamp", timestamp)
                    }
            }
        }

        return if (tokensMap.isNotEmpty()) {
            logInfo(TAG, "getToken: tokensMap $tokensMap")
            JSONObject(tokensMap as Map<*, *>).toString()
        } else {
            null
        }
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
        val placements = mutableListOf<PlacementConfig>()

        for (i in 0 until placementsArray.length()) {
            val obj = placementsArray.getJSONObject(i)
            val placementId = obj.getString("placement_id")
            val format = PlacementFormat.getOrNull(obj.getString("format"))
            if (format != null) {
                placements.add(PlacementConfig(placementId, format))
            }
        }

        return ZmaticooParameters(appKey, placements)
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            MaticooAds.setGDPRConsent(regulation.hasGdprConsent)
        }

        if (regulation.ccpaApplies) {
            context?.let {
                MaticooAds.setDoNotTrackStatus(
                    it,
                    if (regulation.hasCcpaConsent) 0 else 1
                )
            }
        }

        if (regulation.coppaApplies) {
            context?.let {
                MaticooAds.setCoppa(it, 1)
            }
        }
    }

    override fun banner(): AdSource.Banner<ZmaticooBannerAuctionParams> = ZmaticooBannerImpl()

    override fun interstitial(): AdSource.Interstitial<ZmaticooFullscreenAuctionParams> = ZmaticooInterstitialImpl()

    override fun rewarded(): AdSource.Rewarded<ZmaticooFullscreenAuctionParams> = ZmaticooRewardedImpl()
}

private const val TAG = "ZmaticooAdapter"
