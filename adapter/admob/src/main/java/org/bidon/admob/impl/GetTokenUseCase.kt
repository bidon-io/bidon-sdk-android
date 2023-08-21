package org.bidon.admob.impl

import android.content.Context
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdFormat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.admob.REQUEST_AGENT
import org.bidon.admob.ext.asBundle
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.logs.logging.impl.logInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Created by Aleksei Cherniaev on 18/08/2023.
 */
internal class GetTokenUseCase {
    suspend operator fun invoke(context: Context, adType: AdType): String? {
        val adRequest = AdRequest.Builder()
            .bindBiddingParams()
            .build()
        val adFormat = when (adType) {
            AdType.Banner -> AdFormat.BANNER
            AdType.Interstitial -> AdFormat.INTERSTITIAL
            AdType.Rewarded -> AdFormat.REWARDED
        }
        return withTimeoutOrNull<String?>(DefaultTokenTimeoutMs) {
            suspendCoroutine { continuation ->
                QueryInfo.generate(
                    context,
                    adFormat,
                    adRequest,
                    object : QueryInfoGenerationCallback() {
                        override fun onSuccess(queryInfo: QueryInfo) {
                            continuation.resume(queryInfo.query)
                        }

                        override fun onFailure(errorMessage: String) {
                            continuation.resumeWithException(Exception(errorMessage))
                        }
                    }
                )
            }
        }.also {
            logInfo("GetTokenUseCase", "token: $it")
        }
    }

    private fun AdRequest.Builder.bindBiddingParams(): AdRequest.Builder = this.apply {
        val networkExtras = BidonSdk.regulation.asBundle().apply {
//            putString("query_info_type", "requester_type_2") // AppLovin MAX, IronSource - "requester_type_2"
            putString("query_info_type", "requester_type_3") // Chartboost - "requester_type_3"
        }
        setRequestAgent(REQUEST_AGENT)
        addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
    }

    companion object {
        private const val DefaultTokenTimeoutMs = 1000L
    }
}