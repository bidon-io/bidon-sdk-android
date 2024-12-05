package org.bidon.sdk.stats.models

import org.bidon.sdk.config.BidonError

/**
 * Created by Bidon Team on 06/02/2023.
 */
sealed class DemandStatus(val code: String) {
    object Win : DemandStatus("WIN")
    object Lose : DemandStatus("LOSE")
    object NoBid : DemandStatus("NO_BID")
    object NoFill : DemandStatus("NO_FILL") // for Admob only NoBid possible
    object UnknownAdapter : DemandStatus("UNKNOWN_ADAPTER")
    object AdapterNotInitialized : DemandStatus("ADAPTER_NOT_INITIALIZED")
    object BidTimeoutReached : DemandStatus("BID_TIMEOUT_REACHED")
    object FillTimeoutReached : DemandStatus("FILL_TIMEOUT_REACHED")
    object NetworkError : DemandStatus("NETWORK_ERROR")
    class IncorrectAdUnit(val errorMessage: String?) : DemandStatus("INCORRECT_AD_UNIT")
    object NoAppropriateAdUnitId : DemandStatus("NO_APPROPRIATE_AD_UNIT_ID")
    object AuctionCancelled : DemandStatus("AUCTION_CANCELLED")
    object AdFormatNotSupported : DemandStatus("AD_FORMAT_NOT_SUPPORTED")
    class UnspecifiedException(val errorMessage: String?) : DemandStatus("UNSPECIFIED_EXCEPTION")
    object BelowPricefloor : DemandStatus("BELOW_PRICEFLOOR")

    object Successful : DemandStatus("INTERNAL_STATUS") // Internal status, its code should not be used
}

fun Throwable.asDemandStatus() = when (this as? BidonError) {
    is BidonError.AdFormatIsNotSupported -> DemandStatus.AdFormatNotSupported
    is BidonError.BidTimedOut -> DemandStatus.BidTimeoutReached
    is BidonError.FillTimedOut -> DemandStatus.FillTimeoutReached
    is BidonError.InternalServerSdkError,
    is BidonError.NetworkError -> DemandStatus.NetworkError
    BidonError.NoAppropriateAdUnitId -> DemandStatus.NoAppropriateAdUnitId
    is BidonError.NoFill -> DemandStatus.NoFill
    is BidonError.NoBid -> DemandStatus.NoBid
    BidonError.AuctionCancelled -> DemandStatus.AuctionCancelled

    is BidonError.AppKeyIsInvalid -> DemandStatus.UnspecifiedException("AppKeyIsInvalid")
    is BidonError.AdNotReady -> DemandStatus.UnspecifiedException("AdNotReady")
    is BidonError.NoAuctionResults -> DemandStatus.UnspecifiedException("NoAuctionResults")
    is BidonError.NoContextFound -> DemandStatus.UnspecifiedException("NoContextFound")
    is BidonError.NoRoundResults -> DemandStatus.UnspecifiedException("NoRoundResults")
    is BidonError.Expired -> DemandStatus.UnspecifiedException("Expired")
    is BidonError.Unspecified -> DemandStatus.UnspecifiedException((this as BidonError.Unspecified).sourceError?.message)
    is BidonError.IncorrectAdUnit -> DemandStatus.IncorrectAdUnit((this as BidonError.IncorrectAdUnit).message)
    is BidonError.AuctionInProgress -> DemandStatus.UnspecifiedException("AuctionInProgress")
    is BidonError.SdkNotInitialized -> DemandStatus.UnspecifiedException("SdkNotInitialized")
    null -> DemandStatus.UnspecifiedException(null)
}