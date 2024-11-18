package org.bidon.sdk.auction.usecases.models

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo

/**
 * Created by Bidon Team on 26/07/2023.
 */
internal sealed interface ServerBiddingResult {

    object Idle : ServerBiddingResult

    object BiddingStarted : ServerBiddingResult

    class BiddingFinished(
        val tokens: Map<String, TokenInfo>, // TODO: 19/11/2024 [glavatskikh] try to use this tokens instead of tokens inside RequestAdUnitUseCaseImpl
        val noBids: List<AdUnit>,
    ) : ServerBiddingResult
}
