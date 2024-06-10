package org.bidon.sdk.auction.usecases

import android.content.Context
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

internal interface RequestSingleCpm {

    suspend fun invoke(
        context: Context,
        adSource: Mode,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        adUnits: AdUnit,
        priceFloor: Double,
        timeoutMs: Long,
    ): AuctionResult.Network?
}