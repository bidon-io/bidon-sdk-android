package org.bidon.taurusx

import android.content.Context
import com.taurusx.tax.api.BidManager
import com.taurusx.tax.api.TaurusXAds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.bidon.sdk.BuildConfig
import org.bidon.sdk.adapter.AdProvider
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
import org.bidon.sdk.regulation.Coppa
import org.bidon.sdk.regulation.Regulation
import org.bidon.taurusx.impl.TaurusXBannerAuctionParams
import org.bidon.taurusx.impl.TaurusXBannerImpl
import org.bidon.taurusx.impl.TaurusXFullscreenAuctionParams
import org.bidon.taurusx.impl.TaurusXInterstitialImpl
import org.bidon.taurusx.impl.TaurusXRewardedImpl
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "TaurusXAdapter"
internal val TaurusXDemandId = DemandId("taurusx")

internal class TaurusXAdapter() :
    Adapter.Bidding,
    Adapter.Network,
    Initializable<TaurusXParams>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    AdProvider.Banner<TaurusXBannerAuctionParams>,
    AdProvider.Interstitial<TaurusXFullscreenAuctionParams>,
    AdProvider.Rewarded<TaurusXFullscreenAuctionParams> {
    private var placementIds: List<TaurusXPlacement> = mutableListOf()
    override val demandId: DemandId = TaurusXDemandId
    override val adapterInfo: AdapterInfo = AdapterInfo(
        adapterVersion = BuildConfig.ADAPTER_VERSION,
        sdkVersion = TaurusXAds.getSdkVersion()
    )

    override suspend fun getToken(adTypeParam: AdTypeParam): String? =
        coroutineScope {
            JSONObject().apply {
                placementIds.map { (adUnitId, _) ->
                    async {
                        adUnitId to adUnitId.getTokenAsync()
                    }
                }
                    .awaitAll()
                    .forEach { (adUnitId, token) ->
                        put(adUnitId, token ?: "")
                    }
            }.toString()
        }

    private suspend fun String.getTokenAsync(): String? = suspendCoroutine { continuation ->
        BidManager.getInstance().getToken(this) { token ->
            continuation.resume(token)
        }
    }

    override suspend fun init(
        context: Context,
        configParams: TaurusXParams
    ) = suspendCoroutine { continuation ->
        if (TaurusXAds.isInitialized()) {
            logInfo(TAG, "Moloco SDK already initialized")
            continuation.resume(Unit)
            return@suspendCoroutine
        }
        if (configParams.channel.isBlank()) {
            val errorMessage = "Adapter(${TaurusXDemandId.demandId}) channel is empty or blank"
            val error = IllegalArgumentException(errorMessage)
            logError(TAG, errorMessage, error)
            continuation.resumeWithException(error)
            return@suspendCoroutine
        }
        if (configParams.appId.isBlank()) {
            val errorMessage = "Adapter(${TaurusXDemandId.demandId}) appId is empty or blank"
            val error = IllegalArgumentException(errorMessage)
            logError(TAG, errorMessage, error)
            continuation.resumeWithException(error)
            return@suspendCoroutine
        }
        if (isTestMode) {
            TaurusXAds.setTestMode(isTestMode)
        }
        TaurusXAds.setChannel(configParams.channel)
        TaurusXAds.init(context, configParams.appId)
        placementIds = configParams.placementIds
        continuation.resume(Unit)
    }

    override fun parseConfigParam(json: String): TaurusXParams {
        val jsonObject = JSONObject(json)
        return TaurusXParams(
            appId = jsonObject.optString("app_id"),
            channel = jsonObject.optString("channel"),
            placementIds = jsonObject.optJSONArray("placement_ids")?.let { jsonArray ->
                (0 until jsonArray.length()).map { index ->
                    val placementObj = jsonArray.optJSONObject(index) ?: return@map null
                    TaurusXPlacement(
                        adUnitId = placementObj.optString("placement_id"),
                        adFormat = placementObj.optString("format")
                    )
                }.filterNotNull()
            } ?: emptyList()
        )
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            TaurusXAds.setGDPRDataCollection(
                if (regulation.hasGdprConsent) 0 else 1,
            )
        }
        if (regulation.ccpaApplies) {
            TaurusXAds.setCCPADoNotSell(
                if (regulation.hasCcpaConsent) 0 else 1,
            )
        }
        if (regulation.coppa != Coppa.Default) {
            TaurusXAds.setCOPPAIsAgeRestrictedUser(
                if (regulation.coppaApplies) 1 else 0,
            )
        }
    }

    override fun banner() = TaurusXBannerImpl()

    override fun interstitial() = TaurusXInterstitialImpl()

    override fun rewarded() = TaurusXRewardedImpl()
}
