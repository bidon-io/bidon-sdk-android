package org.bidon.moloco

import android.content.Context
import com.moloco.sdk.publisher.Initialization
import com.moloco.sdk.publisher.MediationInfo
import com.moloco.sdk.publisher.Moloco
import com.moloco.sdk.publisher.MolocoAdError
import com.moloco.sdk.publisher.init.MolocoInitParams
import com.moloco.sdk.publisher.privacy.MolocoPrivacy
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.moloco.impl.MolocoBannerAuctionParams
import org.bidon.moloco.impl.MolocoBannerImpl
import org.bidon.moloco.impl.MolocoFullscreenAuctionParams
import org.bidon.moloco.impl.MolocoInterstitialImpl
import org.bidon.moloco.impl.MolocoRewardedImpl
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
import org.bidon.sdk.regulation.Regulation
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.moloco.sdk.BuildConfig as MolocoSdkBuildConfig
import org.bidon.sdk.BuildConfig as BidonBuildConfig

private const val TAG = "MolocoAdapter"
internal val MolocoDemandId = DemandId("moloco")
internal const val EMPTY_MEDIATOR = ""
internal const val EMPTY_WATERMARK = ""

internal class MolocoAdapter :
    Adapter.Bidding,
    Initializable<MolocoParams>,
    SupportsRegulation,
    SupportsTestMode by SupportsTestModeImpl(),
    AdProvider.Banner<MolocoBannerAuctionParams>,
    AdProvider.Interstitial<MolocoFullscreenAuctionParams>,
    AdProvider.Rewarded<MolocoFullscreenAuctionParams> {

    override val demandId: DemandId = MolocoDemandId

    override val adapterInfo: AdapterInfo = AdapterInfo(
        adapterVersion = BidonBuildConfig.ADAPTER_VERSION,
        sdkVersion = MolocoSdkBuildConfig.SDK_VERSION_NAME
    )

    override suspend fun getToken(adTypeParam: AdTypeParam) =
        suspendCancellableCoroutine { continuation ->
            logInfo(TAG, "Requesting bid token")
            Moloco.getBidToken(
                adTypeParam.activity.applicationContext
            ) { bidToken: String, error: MolocoAdError.ErrorType? ->
                if (error != null) {
                    logError(
                        tag = TAG,
                        message = "Failed to get bid token: ${error.name} - ${error.description} (code: ${error.errorCode})",
                        error = null
                    )
                }
                continuation.resume(bidToken)
            }
        }

    override suspend fun init(
        context: Context,
        configParams: MolocoParams
    ) {
        Moloco.initJob?.join()

        logInfo(TAG, "Moloco init start")
        if (Moloco.isInitialized) {
            logInfo(TAG, "Moloco SDK already initialized")
            return
        }

        if (configParams.appKey.isBlank()) {
            val message = "Adapter(${MolocoDemandId.demandId}) app key is empty or blank"
            val error = IllegalArgumentException(message)
            logError(TAG, message, error)
            throw error
        }
        logInfo(TAG, "Moloco appKey not blank")

        val initParams = MolocoInitParams(
            appContext = context.applicationContext,
            appKey = configParams.appKey,
            mediationInfo = MediationInfo(EMPTY_MEDIATOR)
        )

        logInfo(TAG, "Moloco initialize called")

        return suspendCoroutine { continuation ->

            Moloco.initialize(initParams) { status ->
                try {
                    when (status.initialization) {
                        Initialization.SUCCESS -> {
                            logInfo(TAG, "Moloco SDK initialized")
                            continuation.resume(Unit)
                        }

                        Initialization.FAILURE -> {
                            val msg = "Moloco SDK initialization failed: ${status.description}"
                            val err = Exception(msg)
                            logError(TAG, msg, err)
                            continuation.resumeWithException(err)
                        }
                    }
                } catch (throwable: Throwable) {
                    continuation.resumeWithException(throwable)
                }
            }
        }
    }

    override fun parseConfigParam(json: String): MolocoParams {
        val jsonObject = JSONObject(json)
        return MolocoParams(
            appKey = jsonObject.optString("app_key"),
        )
    }

    override fun updateRegulation(regulation: Regulation) {
        if (regulation.gdprApplies) {
            MolocoPrivacy.setPrivacy(
                MolocoPrivacy.PrivacySettings(
                    isUserConsent = regulation.hasGdprConsent,
                )
            )
        }
        if (regulation.ccpaApplies) {
            MolocoPrivacy.setPrivacy(
                MolocoPrivacy.PrivacySettings(
                    isDoNotSell = !regulation.hasCcpaConsent
                )
            )
        }
        MolocoPrivacy.setPrivacy(
            MolocoPrivacy.PrivacySettings(
                isAgeRestrictedUser = regulation.coppaApplies,
            )
        )
    }

    override fun banner() = MolocoBannerImpl()
    override fun interstitial() = MolocoInterstitialImpl()
    override fun rewarded() = MolocoRewardedImpl()
}
