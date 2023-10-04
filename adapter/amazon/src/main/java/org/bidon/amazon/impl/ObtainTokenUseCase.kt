package org.bidon.amazon.impl

import com.amazon.device.ads.AdError
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.amazon.AmazonDemandId
import org.bidon.amazon.SlotType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.getHeightDp
import org.bidon.sdk.ads.banner.helper.getWidthDp
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import kotlin.coroutines.resume

internal class AmazonInfo(
    val dtbAdResponse: DTBAdResponse,
    val adSizes: DTBAdSize,
)

internal class ObtainTokenUseCase {
    suspend operator fun invoke(slots: Map<SlotType, List<String>>, adTypeParam: AdTypeParam): List<AmazonInfo> {
        val filteredSlots = slots.filter { (type, slots) ->
            when (adTypeParam) {
                is AdTypeParam.Banner -> {
                    when (adTypeParam.bannerFormat) {
                        BannerFormat.Banner -> type == SlotType.BANNER
                        BannerFormat.LeaderBoard -> type == SlotType.LEADER_BOARD
                        BannerFormat.MRec -> type == SlotType.MREC
                        BannerFormat.Adaptive -> type == SlotType.BANNER
                    }
                }

                is AdTypeParam.Interstitial -> type == SlotType.INTERSTITIAL
                is AdTypeParam.Rewarded -> error("Not supported")
            }
        }
        val adSizes = getAmazonSizes(filteredSlots)
        return obtainInfo(adSizes)
    }

    private suspend fun obtainInfo(adSizes: Array<DTBAdSize>): List<AmazonInfo> = coroutineScope {
        adSizes
            .map { dtbAdSize ->
                dtbAdSize to async { getDTBAdResponse(dtbAdSize) }
            }.mapNotNull { (dtbAdSize, deferred) ->
                val dtbAdResponse = deferred.await()
                if (dtbAdResponse != null) {
                    AmazonInfo(dtbAdResponse, dtbAdSize)
                } else {
                    null
                }
            }
    }

    private suspend fun getDTBAdResponse(adSize: DTBAdSize): DTBAdResponse? = suspendCancellableCoroutine { continuation ->
        val loader = DTBAdRequest()
        loader.setSizes(adSize)
        loader.loadAd(object : DTBAdCallback {
            override fun onFailure(adError: AdError) {
                logError(TAG, "Error while loading ad: ${adError.code} ${adError.message}", BidonError.NoBid(AmazonDemandId))
                /**Please implement the logic to send ad request without our parameters if you want to
                 * show ads from other ad networks when Amazon ad request fails */
                continuation.resume(null)
            }

            override fun onSuccess(dtbAdResponse: DTBAdResponse) {
                continuation.resume(dtbAdResponse)
            }
        })
    }

    private fun getAmazonSizes(slots: Map<SlotType, List<String>>): Array<DTBAdSize> {
        return slots.flatMap { (type, slotUuids) ->
            slotUuids.map { uuid ->
                when (type) {
                    SlotType.BANNER -> {
                        DTBAdSize(
                            BannerFormat.Banner.getWidthDp(), BannerFormat.Banner.getHeightDp(), uuid
                        )
                    }

                    SlotType.LEADER_BOARD -> {
                        DTBAdSize(
                            BannerFormat.LeaderBoard.getWidthDp(), BannerFormat.LeaderBoard.getHeightDp(), uuid
                        )
                    }

                    SlotType.MREC -> {
                        DTBAdSize(
                            BannerFormat.MRec.getWidthDp(), BannerFormat.MRec.getHeightDp(), uuid
                        )
                    }

                    SlotType.INTERSTITIAL -> {
                        DTBAdSize.DTBInterstitialAdSize(uuid)
                    }
                }
            }
        }.toTypedArray()
    }
}

private const val TAG = "ObtainTokenUseCase"
