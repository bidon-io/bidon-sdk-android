package org.bidon.sdk.ads.cache.andr.token

import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class TokenCollector(
    private val tag: String,
) {
    suspend fun collect(
        adapter: Adapter.Bidding,
        adTypeParam: AdTypeParam,
        tokenTimeout: Long,
    ): TokenInfo {
        logInfo(tag, "Fetching token from ${adapter.demandId.demandId} (timeout=${tokenTimeout}ms)")
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

        logInfo(tag, "Token ${adapter.demandId.demandId}: status=${status}, hasToken=${token != null}")
        return TokenInfo(token, tokenStartTs, SystemTimeNow, status.code)
    }
}
