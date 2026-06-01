package org.bidon.bidmachine

import android.content.Context
import io.bidmachine.BidMachine
import org.bidon.bidmachine.ext.adapterVersion
import org.bidon.bidmachine.ext.sdkVersion
import org.bidon.bidmachine.ext.toAdPlacementConfig
import org.bidon.bidmachine.impl.BMBannerAdImpl
import org.bidon.bidmachine.impl.BMInterstitialAdImpl
import org.bidon.bidmachine.impl.BMRewardedAdImpl
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.*
import org.bidon.sdk.adapter.impl.SupportsTestModeImpl
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.Logger
import org.bidon.sdk.regulation.Regulation
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal val BidMachineDemandId = DemandId("bidmachine")

internal typealias BMAuctionResult = io.bidmachine.models.AuctionResult

@Suppress("unused")
internal class BidMachineAdapter :
    Adapter.Bidding,
    Adapter.Network,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    Initializable<BidMachineParameters>,
    AdProvider.Banner<BMBannerAuctionParams>,
    AdProvider.Rewarded<BMFullscreenAuctionParams>,
    AdProvider.Interstitial<BMFullscreenAuctionParams> {

    override val demandId = BidMachineDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    private var placements: Map<String, String> = emptyMap()

    override suspend fun getToken(adTypeParam: AdTypeParam): String {
        val placementId = adTypeParam.auctionKey?.let { placements[it] }
        val adPlacementConfig = adTypeParam.toAdPlacementConfig(placementId)
        return BidMachine.getBidToken(adTypeParam.activity.applicationContext, adPlacementConfig)
    }

    override suspend fun init(context: Context, configParams: BidMachineParameters): Unit =
        suspendCoroutine { continuation ->
            this.placements = configParams.placements ?: emptyMap()
            val sourceId = configParams.sellerId
            BidMachine.setTestMode(isTestMode)
            BidMachine.setLoggingEnabled(BidonSdk.loggerLevel != Logger.Level.Off)
            BidMachine.initialize(context, sourceId) {
                continuation.resume(Unit)
            }
        }

    override fun parseConfigParam(json: String): BidMachineParameters {
        val jsonObject = JSONObject(json)
        return BidMachineParameters(
            sellerId = jsonObject.getString("seller_id"),
            endpoint = jsonObject.optString("endpoint", "").takeIf { !it.isNullOrBlank() },
            placements = jsonObject.optJSONObject("placements")?.let { placements ->
                buildMap {
                    placements.keys().forEach { key ->
                        put(key, placements.optString(key))
                    }
                }
            }
        )
    }

    override fun updateRegulation(regulation: Regulation) {
        regulation.usPrivacyString?.let { usPrivacyString ->
            // Parse US Privacy String (format: 1YNN, position 3 indicates opt-out)
            // If position 3 is 'Y', user opted out, so non-personalized ads should be used
            val optedOut = usPrivacyString.length > 2 && usPrivacyString[2] == 'Y'
            BidMachine.setNonPersonalized(optedOut)
        }
        if (regulation.coppaApplies) {
            BidMachine.setCoppa(true)
        }
        if (regulation.gdprApplies) {
            BidMachine.setSubjectToGDPR(true)
            regulation.gdprConsentString
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    // BidMachine now reads consent string from IAB TCF 2.0 SharedPreferences
                    BidMachine.setConsentStatus(regulation.hasGdprConsent)
                }
        }
    }

    override fun interstitial(): AdSource.Interstitial<BMFullscreenAuctionParams> = BMInterstitialAdImpl()
    override fun rewarded(): AdSource.Rewarded<BMFullscreenAuctionParams> = BMRewardedAdImpl()
    override fun banner(): AdSource.Banner<BMBannerAuctionParams> = BMBannerAdImpl()
}