package org.bidon.sdk.auction.impl

import kotlinx.coroutines.flow.MutableStateFlow
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionHolder
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.asFailure
import org.bidon.sdk.utils.ext.asSuccess

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
internal class AuctionHolderImpl(
    private val demandAd: DemandAd,
) : AuctionHolder {
    private val auctionState = MutableStateFlow<AuctionHolderState>(AuctionHolderState.Idle)
    private var displayingWinner: AuctionResult? = null
    private var nextWinner: AuctionResult? = null

    override val isAuctionActive: Boolean
        get() = auctionState.value is AuctionHolderState.InProgress

    override fun startAuction(
        adTypeParam: AdTypeParam,
        onResult: (Result<List<AuctionResult>>) -> Unit
    ) {
        val progressState = AuctionHolderState.InProgress()
        if (auctionState.compareAndSet(expect = AuctionHolderState.Idle, update = progressState)) {
            progressState.auction.start(
                demandAd = demandAd,
                resolver = MaxEcpmAuctionResolver,
                adTypeParamData = adTypeParam,
                onSuccess = { results ->
                    check(results.isNotEmpty()) {
                        "Auction succeed if results is not empty"
                    }
                    logInfo(Tag, "Auction completed successfully: $results")
                    nextWinner = results.first()
                    onResult.invoke(results.asSuccess())
                    auctionState.value = AuctionHolderState.Idle
                },
                onFailure = {
                    nextWinner = null
                    logError(Tag, "Auction failed", it)
                    onResult.invoke(it.asFailure())
                    auctionState.value = AuctionHolderState.Idle
                }
            )
        } else {
            onResult.invoke(BidonError.AuctionInProgress.asFailure())
        }
    }

    override fun popWinner(): AdSource<*>? {
        synchronized(this) {
            displayingWinner?.adSource?.destroy()
            displayingWinner = nextWinner
            nextWinner = null
            return displayingWinner?.adSource
        }
    }

    override fun getNextLoadedWinner(): AdSource<*>? {
        return nextWinner?.adSource
    }

    override fun destroy() {
        (auctionState.value as? AuctionHolderState.InProgress)?.auction?.cancel()
        auctionState.value = AuctionHolderState.Idle
        displayingWinner?.adSource?.destroy()
        displayingWinner = null
        nextWinner?.adSource?.destroy()
        nextWinner = null
    }

    override fun isAdReady(): Boolean {
        return nextWinner?.adSource?.isAdReadyToShow == true
    }
}

@Suppress("CanSealedSubClassBeObject")
internal sealed interface AuctionHolderState {
    object Idle : AuctionHolderState
    class InProgress : AuctionHolderState {
        val auction: Auction by lazy { get() }
    }
}

private const val Tag = "AuctionHolder"