package org.bidon.sdk.auction.usecases

import kotlinx.coroutines.Deferred
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.auction.models.LineItem

/**
 * Created by Aleksei Cherniaev on 01/06/2023.
 */
internal class DeferredAdEvent(
    val adEvent: AdEvent,
    val adSource: AdSource<AdAuctionParams>?,
)

internal class RoundNetworkResult(
    val results: List<Deferred<DeferredAdEvent>>,
    /**
     * Remaining LineItems, excluded consumed ones
     */
    val remainingLineItems: List<LineItem>
)
