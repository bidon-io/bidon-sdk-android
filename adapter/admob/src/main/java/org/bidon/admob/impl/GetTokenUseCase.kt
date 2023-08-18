package org.bidon.admob.impl

import android.content.Context
import com.google.android.gms.ads.AdFormat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.admob.ext.bindBiddingParams
import org.bidon.sdk.ads.AdType
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
        return withTimeoutOrNull(DefaultTokenTimeoutMs) {
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
        }
    }

    companion object {
        private const val DefaultTokenTimeoutMs = 1000L
    }
}