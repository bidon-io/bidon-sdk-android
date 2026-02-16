package org.bidon.mobilefuse.ext

import android.content.Context
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenProvider
import com.mobilefuse.sdk.internal.MobileFuseBiddingTokenRequest
import com.mobilefuse.sdk.internal.TokenGeneratorListener
import org.bidon.sdk.BidonSdk
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Created by Bidon Team on 27/09/2023.
 */
internal object GetMobileFuseTokenUseCase {
    suspend operator fun invoke(context: Context, isTestMode: Boolean): String? {
        // Create our token request // Create our token request
        val tokenRequest = MobileFuseBiddingTokenRequest(
            privacyPreferences = BidonSdk.regulation.toMobileFusePrivacyPreferences(),
            isTestMode = isTestMode
        )
        return suspendCoroutine {
            // Generate a token - asynchronous:
            MobileFuseBiddingTokenProvider.getToken(
                tokenRequest,
                context,
                object : TokenGeneratorListener {
                    override fun onTokenGenerated(token: String) {
                        it.resume(token)
                    }

                    override fun onTokenGenerationFailed(error: String) {
                        it.resume(null)
                    }
                }
            )
        }
    }
}