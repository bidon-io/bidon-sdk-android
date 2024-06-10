package org.bidon.sdk.auction.usecases

import android.content.Context
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

interface RequestSingleRtb {

    suspend fun invoke(
        context: Context,
        adSource: Mode,
        adUnit: AdUnit,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        priceFloor: Double,
        timeoutMs: Long,
    ) : AuctionResult.Bidding?
}