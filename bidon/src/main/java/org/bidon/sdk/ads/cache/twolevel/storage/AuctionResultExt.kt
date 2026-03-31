package org.bidon.sdk.ads.cache.twolevel.storage

import org.bidon.sdk.auction.models.AuctionResult

internal fun AuctionResult.price(): Double = adSource.getStats().price
internal fun AuctionResult.demandKey(): String = adSource.getStats().demandId.demandId
