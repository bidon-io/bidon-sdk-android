package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.auction.models.AuctionResult

internal val AuctionResult.demandId: String
    get() = adSource.getStats().demandId.demandId

internal val AuctionResult.price: Double
    get() = adSource.getStats().price
