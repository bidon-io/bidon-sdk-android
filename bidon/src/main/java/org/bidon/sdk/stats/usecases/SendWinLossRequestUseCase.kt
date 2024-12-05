package org.bidon.sdk.stats.usecases

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.stats.models.ImpressionRequestBody
import org.bidon.sdk.stats.models.Winner
import org.bidon.sdk.utils.networking.BaseResponse

/**
 * Created by Bidon Team on 06/04/2023.
 */
internal interface SendWinLossRequestUseCase {
    suspend operator fun invoke(
        data: WinLossRequestData
    ): Result<BaseResponse>
}

internal sealed interface WinLossRequestData {
    val demandAd: DemandAd
    val bidBody: ImpressionRequestBody

    data class Loss(
        val winnerBody: Winner,
        override val demandAd: DemandAd,
        override val bidBody: ImpressionRequestBody
    ) : WinLossRequestData

    data class Win(
        override val demandAd: DemandAd,
        override val bidBody: ImpressionRequestBody
    ) : WinLossRequestData
}