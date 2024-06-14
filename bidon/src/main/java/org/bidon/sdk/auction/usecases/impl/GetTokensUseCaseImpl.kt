package org.bidon.sdk.auction.usecases.impl

import android.content.Context
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow
import org.bidon.sdk.utils.ext.TAG

internal class GetTokensUseCaseImpl : GetTokensUseCase {
    override suspend fun invoke(
        adType: AdType,
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
    ): Map<String, TokenInfo> {
        /**
         * Bidding demands auction
         */
        val filteredBiddingAdapters =
            adaptersSource.adapters.filterIsInstance<Adapter.Bidding>().onEach(::applyRegulation)

        /**
         * Tokens Obtaining
         */
        val tokens = filteredBiddingAdapters.getTokens(
            context = adTypeParam.activity.applicationContext,
            adTypeParam = adTypeParam,
            tokenTimeout = tokenTimeout
        ).onEach { pair ->
            logInfo(TAG, "#${pair.key} {${pair.value?.token}}")
        }
        val filtered = tokens.filterValues { it?.status == TokenInfo.Status.SUCCESS.code }
            .mapNotNull { (key, value) -> value?.let { key to it } }
            .toMap()

        return if (filtered.isEmpty()) {
            logError(TAG, "No tokens found", BidonError.NoBid)
            emptyMap()
        } else {
            logInfo(TAG, "${filtered.size} token(s):")
            filtered
        }
    }

    private fun applyRegulation(adapter: Adapter) {
        (adapter as? SupportsRegulation)?.let { supportsRegulation ->
            logInfo(
                TAG,
                "Applying regulation to ${adapter.demandId.demandId} <- " +
                        "GDPR=${BidonSdk.regulation.gdpr}, " +
                        "COPPA=${BidonSdk.regulation.coppa}, " +
                        "usPrivacyString=${BidonSdk.regulation.usPrivacyString}, " +
                        "gdprConsentString=${BidonSdk.regulation.gdprConsentString}"
            )
            supportsRegulation.updateRegulation(BidonSdk.regulation)
        }
    }

    private suspend fun List<Adapter.Bidding>.getTokens(
        context: Context,
        adTypeParam: AdTypeParam,
        tokenTimeout: Long,
    ): Map<String, TokenInfo?> =
        this.associate { adapter ->
            adapter.demandId.demandId to runCatching {
                val tokenStartTs = SystemTimeNow
                val token = withTimeoutOrNull(tokenTimeout) {
                    adapter.getToken(
                        context = context,
                        adTypeParam = adTypeParam,
                    )
                }
                val tokenFinishTs = SystemTimeNow
                val status = when {
                    token == null -> TokenInfo.Status.TIMEOUT_REACHED
                    token.isEmpty() -> TokenInfo.Status.NO_TOKEN
                    else -> TokenInfo.Status.SUCCESS
                }
                TokenInfo(
                    token = token,
                    tokenStartTs = tokenStartTs,
                    tokenFinishTs = tokenFinishTs,
                    status = status.code
                )
            }.getOrNull()
        }
}
