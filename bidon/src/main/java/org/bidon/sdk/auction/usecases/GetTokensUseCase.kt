package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo

internal interface GetTokensUseCase {
    /**
     * Collect tokens from bidding adapters.
     *
     * @param adTypeParam Ad type parameters
     * @param adaptersSource Source of adapters to collect tokens from
     * @param tokenTimeout Timeout for token collection per adapter
     * @param skipDemandIds Set of demand IDs to skip (cached RTB payloads)
     * @return Map of demandId to TokenInfo
     */
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String> = emptySet(),
    ): Map<String, TokenInfo>
}