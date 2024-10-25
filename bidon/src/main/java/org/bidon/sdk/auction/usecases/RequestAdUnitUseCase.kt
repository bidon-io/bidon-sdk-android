package org.bidon.sdk.auction.usecases

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.TokenInfo

internal interface RequestAdUnitUseCase {

    suspend fun invoke(
        adSource: AdSource<AdAuctionParams>,
        adUnit: AdUnit,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
        tokenInfo: TokenInfo?, // TODO: 28/10/2024 [glavatskikh] change logic with DemandResult.kt and TokenInfo.kt
    ): DemandResult
}