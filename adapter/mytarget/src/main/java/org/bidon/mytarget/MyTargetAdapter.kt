package org.bidon.mytarget

import android.content.Context
import com.my.target.common.MyTargetManager
import com.my.target.common.MyTargetPrivacy
import org.bidon.mytarget.ext.adapterVersion
import org.bidon.mytarget.ext.sdkVersion
import org.bidon.mytarget.impl.MyTargetBannerImpl
import org.bidon.mytarget.impl.MyTargetFullscreenAuctionParams
import org.bidon.mytarget.impl.MyTargetIntersititialImpl
import org.bidon.mytarget.impl.MyTargetRewardedAdImpl
import org.bidon.mytarget.impl.MyTargetViewAuctionParams
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

val MyTargetDemandId = DemandId("mytarget")

class MyTargetAdapter :
    Adapter.Bidding,
    Adapter.Network,
    Initializable<MyTargetParams>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    AdProvider.Banner<MyTargetViewAuctionParams>,
    AdProvider.Interstitial<MyTargetFullscreenAuctionParams>,
    AdProvider.Rewarded<MyTargetFullscreenAuctionParams> {
    override val demandId: DemandId = MyTargetDemandId
    override val adapterInfo: AdapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    override suspend fun getToken(context: Context, adTypeParam: AdTypeParam) =
        MyTargetManager.getBidderToken(context)

    override suspend fun init(context: Context, configParams: MyTargetParams) {
        if (isTestMode) {
            MyTargetManager.setDebugMode(true)
        }
        MyTargetManager.initSdk(context)
    }

    override fun parseConfigParam(json: String): MyTargetParams {
        return MyTargetParams()
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

    override fun interstitial(): AdSource.Interstitial<MyTargetFullscreenAuctionParams> =
        MyTargetIntersititialImpl()

    override fun banner(): AdSource.Banner<MyTargetViewAuctionParams> =
        MyTargetBannerImpl()

    override fun rewarded(): AdSource.Rewarded<MyTargetFullscreenAuctionParams> =
        MyTargetRewardedAdImpl()
}