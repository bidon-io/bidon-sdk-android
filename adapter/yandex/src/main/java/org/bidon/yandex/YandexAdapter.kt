package org.bidon.yandex

import android.content.Context
import com.yandex.mobile.ads.common.MobileAds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource.Banner
import org.bidon.sdk.adapter.AdSource.Interstitial
import org.bidon.sdk.adapter.AdSource.Rewarded
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.adapter.SupportsTestMode
import org.bidon.sdk.adapter.impl.SupportsTestModeImpl
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.regulation.Regulation
import org.bidon.yandex.ext.adapterVersion
import org.bidon.yandex.ext.sdkVersion
import org.bidon.yandex.impl.YandexBannerAuctionParam
import org.bidon.yandex.impl.YandexBannerImpl
import org.bidon.yandex.impl.YandexFullscreenAuctionParam
import org.bidon.yandex.impl.YandexInterstitialImpl
import org.bidon.yandex.impl.YandexRewardedImpl
import kotlin.coroutines.resume

/**
 * Created by Bidon Team on 07/08/2024.
 */
internal val YandexDemandId = DemandId("yandex")

@Suppress("unused")
internal class YandexAdapter :
    Adapter.Network,
    Adapter.Bidding,
    Initializable<YandexParameters>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    AdProvider.Banner<YandexBannerAuctionParam>,
    AdProvider.Interstitial<YandexFullscreenAuctionParam>,
    AdProvider.Rewarded<YandexFullscreenAuctionParam> {

    override val demandId: DemandId get() = YandexDemandId
    override val adapterInfo: AdapterInfo = AdapterInfo(
        adapterVersion = adapterVersion,
        sdkVersion = sdkVersion
    )

    private val getYandexToken: GetYandexTokenUseCase by lazy { GetYandexTokenUseCase() }

    override suspend fun getToken(adTypeParam: AdTypeParam): String? = getYandexToken(adTypeParam)

    override suspend fun init(context: Context, configParams: YandexParameters) =
        suspendCancellableCoroutine {
            MobileAds.enableLogging(isTestMode)
            MobileAds.initialize(context) { it.resume(Unit) }
        }

    override fun parseConfigParam(json: String): YandexParameters = YandexParameters()

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            MobileAds.setUserConsent(regulation.hasGdprConsent)
        }
        if (regulation.coppaApplies) {
            MobileAds.setAgeRestrictedUser(true)
        }
    }

    override fun banner(): Banner<YandexBannerAuctionParam> = YandexBannerImpl()
    override fun interstitial(): Interstitial<YandexFullscreenAuctionParam> = YandexInterstitialImpl()
    override fun rewarded(): Rewarded<YandexFullscreenAuctionParam> = YandexRewardedImpl()
}