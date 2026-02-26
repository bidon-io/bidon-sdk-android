package org.bidon.sdk.ads.cache.andr.token

import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class TokenCollector {
    suspend fun collect(
        adapter: Adapter.Bidding,
        adTypeParam: AdTypeParam,
        tokenTimeout: Long,
    ): TokenInfo {
        val tokenStartTs = SystemTimeNow
        // Fetch token with a timeout
        val (token, status) =
            withTimeoutOrNull(tokenTimeout) {
                try {
                    adapter
                        .getToken(adTypeParam)
                        ?.let { it to TokenInfo.Status.SUCCESS }
                        ?: (null to TokenInfo.Status.NO_TOKEN)
                } catch (_: Exception) {
                    (null to TokenInfo.Status.NO_TOKEN)
                }
            } ?: (null to TokenInfo.Status.TIMEOUT_REACHED)

        return TokenInfo(token, tokenStartTs, SystemTimeNow, status.code)
    }
}
