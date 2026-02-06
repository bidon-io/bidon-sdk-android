package org.bidon.sdk.ads.cache.denis.usecases

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * V2-specific wrapper that filters out cached demand IDs before
 * delegating to the original GetTokensUseCase.
 *
 * Isolation strategy: Common SDK GetTokensUseCase remains unchanged.
 * V2 skip optimization is handled entirely within the denis package.
 */
internal class GetTokensWithSkipUseCase(
    private val delegate: GetTokensUseCase,
) {
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String>,
    ): Map<String, TokenInfo> {
        if (skipDemandIds.isEmpty()) {
            return delegate(adTypeParam, adaptersSource, tokenTimeout)
        }

        // Log skip info for debugging
        val allBiddingCount = adaptersSource.adapters.count { it is Adapter.Bidding }
        logInfo(TAG, "Token collection: skipping ${skipDemandIds.size} of $allBiddingCount bidding adapters (cached RTB payloads)")
        skipDemandIds.forEach { demandId ->
            logInfo(TAG, "Skipped token collection for demandId=$demandId (cached payload)")
        }

        // Create filtered adapters source that excludes cached demand IDs
        val filteredAdaptersSource = FilteredAdaptersSource(adaptersSource, skipDemandIds)

        return delegate(adTypeParam, filteredAdaptersSource, tokenTimeout)
    }

    companion object {
        private const val TAG = "[DenisCache] GetTokensWithSkipUseCase"
    }
}

/**
 * AdaptersSource wrapper that excludes adapters with specific demand IDs.
 * Used by GetTokensWithSkipUseCase to filter before delegation.
 */
private class FilteredAdaptersSource(
    private val delegate: AdaptersSource,
    private val excludeDemandIds: Set<String>,
) : AdaptersSource {
    override val adapters: Set<Adapter>
        get() = delegate.adapters.filter { adapter ->
            adapter.demandId.demandId !in excludeDemandIds
        }.toSet()

    override fun add(adapter: Adapter) {
        delegate.add(adapter)
    }
}
