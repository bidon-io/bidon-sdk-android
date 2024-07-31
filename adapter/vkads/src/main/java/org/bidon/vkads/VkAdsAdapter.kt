package org.bidon.vkads

import android.content.Context
import com.my.target.common.MyTargetManager
import com.my.target.common.MyTargetPrivacy
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
import org.bidon.sdk.regulation.Regulation
import org.bidon.vkads.ext.adapterVersion
import org.bidon.vkads.ext.sdkVersion
import org.bidon.vkads.impl.VkAdsFullscreenAuctionParams
import org.bidon.vkads.impl.VkAdsViewAuctionParams
import org.bidon.vkads.impl.VkAdsBannerImpl
import org.bidon.vkads.impl.VkAdsInterstitialImpl
import org.bidon.vkads.impl.VkAdsRewardedAdImpl

internal val VkAdsDemandId = DemandId("vk_ads")

@Suppress("unused")
internal class VkAdsAdapter :
    Adapter.Bidding,
    Adapter.Network,
    Initializable<VkAdsParameters>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    AdProvider.Banner<VkAdsViewAuctionParams>,
    AdProvider.Interstitial<VkAdsFullscreenAuctionParams>,
    AdProvider.Rewarded<VkAdsFullscreenAuctionParams> {

    override val demandId = VkAdsDemandId
    override val adapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override suspend fun getToken(context: Context, adTypeParam: AdTypeParam) =
        MyTargetManager.getBidderToken(context)

    override suspend fun init(context: Context, configParams: VkAdsParameters) {
        if (isTestMode) {
            MyTargetManager.setDebugMode(true)
        }
        MyTargetManager.initSdk(context)
    }

    override fun parseConfigParam(json: String): VkAdsParameters {
        return VkAdsParameters()
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            MyTargetPrivacy.setUserConsent(regulation.hasGdprConsent)
        }
        if (regulation.ccpaApplies) {
            MyTargetPrivacy.setCcpaUserConsent(regulation.ccpaApplies)
        }
        if (regulation.coppaApplies) {
            MyTargetPrivacy.setUserAgeRestricted(regulation.coppaApplies)
        }
    }

    override fun interstitial(): AdSource.Interstitial<VkAdsFullscreenAuctionParams> =
        VkAdsInterstitialImpl()

    override fun banner(): AdSource.Banner<VkAdsViewAuctionParams> =
        VkAdsBannerImpl()

    override fun rewarded(): AdSource.Rewarded<VkAdsFullscreenAuctionParams> =
        VkAdsRewardedAdImpl()
}