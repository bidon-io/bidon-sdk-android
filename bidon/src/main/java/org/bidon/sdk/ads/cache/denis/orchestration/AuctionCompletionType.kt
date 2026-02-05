package org.bidon.sdk.ads.cache.denis.orchestration

/**
 * Result type from coordinateAuction() indicating how the auction completed.
 *
 * CRITICAL CONTRACT:
 * - When WarmStartServed is returned, the caller MUST NOT invoke coordinateAuction() again
 * - Warm start means a cached ad was served immediately; no background auction is permitted
 * - This enforces the decision: "No background refresh on warm start"
 */
internal sealed class AuctionCompletionType {
    /**
     * A cached ad was served immediately from READY_TO_SHOW cache.
     *
     * CONTRACT: Caller MUST NOT start another auction when this is returned.
     * The auction is complete - no background refresh is allowed.
     */
    data object WarmStartServed : AuctionCompletionType()

    /**
     * Cold start auction completed (either success or failure via callbacks).
     */
    data object ColdStartCompleted : AuctionCompletionType()

    /**
     * Cold start auction is in progress (parallel processing ongoing).
     * Results will arrive via callbacks.
     */
    data object ColdStartInProgress : AuctionCompletionType()
}
