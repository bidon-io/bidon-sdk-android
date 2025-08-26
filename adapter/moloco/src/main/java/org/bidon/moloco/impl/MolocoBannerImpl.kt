package org.bidon.moloco.impl

import com.moloco.sdk.publisher.AdLoad
import com.moloco.sdk.publisher.Banner
import com.moloco.sdk.publisher.BannerAdShowListener
import com.moloco.sdk.publisher.MediationInfo
import com.moloco.sdk.publisher.Moloco
import com.moloco.sdk.publisher.MolocoAd
import com.moloco.sdk.publisher.MolocoAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bidon.moloco.EMPTY_MEDIATOR
import org.bidon.moloco.EMPTY_WATERMARK
import org.bidon.moloco.MolocoDemandId
import org.bidon.moloco.ext.toBidonLoadError
import org.bidon.moloco.ext.toBidonShowError
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdViewHolder
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.DeviceInfo.isTablet
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MolocoBannerImpl :
    AdSource.Banner<MolocoBannerAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var bannerAd: Banner? = null
    override val isAdReadyToShow: Boolean
        get() = bannerAd?.isLoaded == true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val loadListener = object : AdLoad.Listener {
        override fun onAdLoadSuccess(molocoAd: MolocoAd) {
            logInfo(TAG, "Banner ad loaded successfully")
            getAd()?.let { emitEvent(AdEvent.Fill(it)) }
        }

        override fun onAdLoadFailed(molocoAdError: MolocoAdError) {
            val cause = molocoAdError.toBidonLoadError()
            logError(TAG, "Banner ad load failed: ${molocoAdError.description}", cause)
            emitEvent(AdEvent.LoadFailed(cause))
        }
    }

    private val showListener = object : BannerAdShowListener {
        override fun onAdShowSuccess(molocoAd: MolocoAd) {
            logInfo(TAG, "Banner ad shown successfully")
            getAd()?.let { ad ->
                emitEvent(
                    AdEvent.PaidRevenue(
                        ad = ad,
                        adValue = AdValue(
                            adRevenue = molocoAd.revenue?.toDouble() ?: 0.0,
                            currency = AdValue.USD,
                            precision = Precision.Precise
                        )
                    )
                )
            }
        }

        override fun onAdShowFailed(molocoAdError: MolocoAdError) {
            logInfo(TAG, "Banner ad show failed: ${molocoAdError.description}")
            emitEvent(
                AdEvent.ShowFailed(
                    BidonError.Unspecified(
                        demandId,
                        molocoAdError.toBidonShowError()
                    )
                )
            )
        }

        override fun onAdHidden(molocoAd: MolocoAd) {
            // ignore
        }

        override fun onAdClicked(molocoAd: MolocoAd) {
            logInfo(TAG, "Banner ad clicked")
            getAd()?.let { emitEvent(AdEvent.Clicked(it)) }
        }
    }

    override fun getAdView(): AdViewHolder? {
        return bannerAd?.let { banner ->
            banner.adShowListener = showListener
            AdViewHolder(banner)
        }
    }

    override fun load(adParams: MolocoBannerAuctionParams) {
        logInfo(TAG, "Starting banner load with format: ${adParams.bannerFormat}")

        val adUnitId = adParams.adUnitId
        if (adUnitId == null) {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(
                        demandId = demandId,
                        message = "adUnitId is required"
                    )
                )
            )
            return
        }

        val payload = adParams.payload
        if (payload == null) {
            emitEvent(
                AdEvent.LoadFailed(
                    BidonError.IncorrectAdUnit(demandId = demandId, message = "payload is required")
                )
            )
            return
        }
        scope.launch {
            try {
                val banner = createViewAd(adParams.bannerFormat, adUnitId)
                bannerAd = banner
                banner.load(payload, listener = loadListener)
            } catch (e: Exception) {
                logError(TAG, "Failed to create or load banner ad", e)
                emitEvent(
                    AdEvent.LoadFailed(
                        BidonError.Unspecified(
                            MolocoDemandId,
                            message = e.message ?: "Failed to create banner ad"
                        )
                    )
                )
            }
        }
    }

    private suspend fun createViewAd(bannerFormat: BannerFormat, adUnitId: String): Banner {
        return suspendCancellableCoroutine { continuation ->
            val mediationInfo = MediationInfo(EMPTY_MEDIATOR)

            val actualFormat = if (bannerFormat == BannerFormat.Adaptive) {
                if (isTablet) BannerFormat.LeaderBoard else BannerFormat.Banner
            } else {
                bannerFormat
            }

            val callback: (Banner?, MolocoAdError.AdCreateError?) -> Unit = { banner, error ->
                if (banner != null) {
                    continuation.resume(banner)
                } else {
                    val errorMessage = error?.description ?: "Created viewAd is null"
                    continuation.resumeWithException(Exception(errorMessage))
                }
            }

            when (actualFormat) {
                BannerFormat.Banner -> {
                    Moloco.createBanner(
                        mediationInfo = mediationInfo,
                        adUnitId = adUnitId,
                        watermarkString = EMPTY_WATERMARK,
                        callback = callback
                    )
                }

                BannerFormat.LeaderBoard -> {
                    Moloco.createBannerTablet(
                        mediationInfo = mediationInfo,
                        adUnitId = adUnitId,
                        watermarkString = EMPTY_WATERMARK,
                        callback = callback
                    )
                }

                BannerFormat.MRec -> {
                    Moloco.createMREC(
                        mediationInfo = mediationInfo,
                        adUnitId = adUnitId,
                        watermarkString = EMPTY_WATERMARK,
                        callback = callback
                    )
                }

                BannerFormat.Adaptive -> {
                    continuation.resumeWithException(
                        IllegalStateException("Adaptive format should have been resolved")
                    )
                }
            }
        }
    }

    override fun destroy() {
        logInfo(TAG, "Destroying banner ad")
        bannerAd?.adShowListener = null
        bannerAd?.destroy()
        bannerAd = null
        scope.cancel()
    }

    override fun getAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return ObtainAuctionParamUseCase().getBannerParam(auctionParamsScope)
    }
}

private const val TAG = "MolocoBannerImpl"
