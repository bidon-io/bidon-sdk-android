package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource

/**
 * Created by Aleksei Cherniaev on 01/06/2023.
 */
internal class PollItem(
    val adEvent: AdEvent,
    val adSource: AdSource<AdAuctionParams>?,
)