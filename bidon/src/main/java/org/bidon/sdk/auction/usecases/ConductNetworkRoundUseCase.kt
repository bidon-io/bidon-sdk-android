package org.bidon.sdk.auction.usecases

import android.content.Context
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal interface ConductNetworkRoundUseCase {
    /**
     * @param participantIds Bidding Demand Ids
     */
    suspend fun invoke(
        context: Context,
        adSource: AdSource<AdAuctionParams>,
        adUnit: AdUnit,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        priceFloor: Double,
        timeoutMs: Long,
    ): AuctionResult?
}
