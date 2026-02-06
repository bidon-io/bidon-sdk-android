package org.bidon.sdk.ads.cache.denis.stores

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo

/**
 * RTB bid response payload that can be reused for loading ads without re-running auction.
 *
 * This payload represents a winning bid from a previous auction that wasn't loaded.
 * It can be cached and reused in subsequent loadAd() calls, enabling skip-token optimization.
 *
 * @property adUnit The ad unit from auction response containing demandId, pricefloor, etc.
 * @property tokenInfo Optional token info for the payload (if token was collected)
 * @property auctionId Auction identifier for tracking
 */
internal data class RtbPayload(
    val adUnit: AdUnit,
    val tokenInfo: TokenInfo?,
    val auctionId: String
)
