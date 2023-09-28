package org.bidon.amazon.impl

import com.amazon.device.ads.AdError
import com.amazon.device.ads.DTBAdCallback
import com.amazon.device.ads.DTBAdRequest
import com.amazon.device.ads.DTBAdResponse
import com.amazon.device.ads.DTBAdSize
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.amazon.AmazonDemandId
import org.bidon.amazon.SlotType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.getHeightDp
import org.bidon.sdk.ads.banner.helper.getWidthDp
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import kotlin.coroutines.resume

internal class ObtainTokenUseCase {
    suspend operator fun invoke(slots: Map<SlotType, List<String>>, bannerFormat: BannerFormat): String? =
        suspendCancellableCoroutine { continuation ->
            val loader = DTBAdRequest()
            loader.setSizes(
                *getAmazonSizes(slots, bannerFormat)
            )
            loader.loadAd(object : DTBAdCallback {
                override fun onFailure(adError: AdError) {
                    logError(TAG, "Error while loading ad: ${adError.code} ${adError.message}", BidonError.NoBid(AmazonDemandId))
                    /**Please implement the logic to send ad request without our parameters if you want to
                     * show ads from other ad networks when Amazon ad request fails */
                    /**Please implement the logic to send ad request without our parameters if you want to
                     * show ads from other ad networks when Amazon ad request fails */
                    continuation.resume(null)
                }

                override fun onSuccess(dtbAdResponse: DTBAdResponse) {
                    val custParams = dtbAdResponse.defaultDisplayAdsRequestCustomParams
                    logInfo(TAG, "Ad loaded with custParams: $custParams")

                    //Loop through custParams and forward the targeting to your ad server
                }
            })
        }

    private fun getAmazonSizes(slots: Map<SlotType, List<String>>, bannerFormat: BannerFormat): Array<DTBAdSize> {
        return slots.flatMap { (type, slotUuids) ->
            slotUuids.map {uuid->
                when (type) {
                    SlotType.BANNER -> {
                        DTBAdSize(
                            BannerFormat.Banner.getWidthDp(),
                            BannerFormat.Banner.getHeightDp(),
                            uuid
                        )
                    }

                    SlotType.LEADER_BOARD -> {
                        DTBAdSize(
                            BannerFormat.LeaderBoard.getWidthDp(),
                            BannerFormat.LeaderBoard.getHeightDp(),
                            uuid
                        )
                    }

                    SlotType.MREC -> {
                        DTBAdSize(
                            BannerFormat.MRec.getWidthDp(),
                            BannerFormat.MRec.getHeightDp(),
                            uuid
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
