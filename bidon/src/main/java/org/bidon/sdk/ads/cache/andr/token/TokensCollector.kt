package org.bidon.sdk.ads.cache.andr.token

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.logs.logging.impl.logInfo

internal class TokensCollector(
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val biddingConfig: BiddingConfig,
    private val tokenCollector: TokenCollector,
) {
    suspend fun collect(
        adTypeParam: AdTypeParam,
        adapters: Collection<Adapter.Bidding>,
    ): Map<String, TokenInfo> =
        withContext(ioDispatcher) {
            adapters
                .map { collect(adTypeParam, it.demandId.demandId, it) }
                .awaitAll()
                .toMap()
                .also { logInfo(tag, "Collected ${it.size} tokens") }
        }

    private fun CoroutineScope.collect(
        adTypeParam: AdTypeParam,
        demandId: String,
        adapter: Adapter.Bidding,
    ): Deferred<Pair<String, TokenInfo>> =
        async {
            demandId to tokenCollector.collect(adapter, adTypeParam, biddingConfig.tokenTimeout)
        }

}
