package org.bidon.admob.impl

import android.annotation.SuppressLint
import android.content.Context
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.admob.AdmobBannerAuctionParams
import org.bidon.admob.DefaultTokenTimeoutMs
import org.bidon.admob.asBidonError
import org.bidon.admob.ext.adaptiveAdSize
import org.bidon.admob.ext.asBidonAdValue
import org.bidon.admob.ext.asBundle
import org.bidon.admob.ext.bindBiddingParams
import org.bidon.admob.ext.bindFillParams
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.*
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.helper.getHeightDp
import org.bidon.sdk.ads.banner.helper.getWidthDp
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * [Test ad units](https://developers.google.com/admob/android/test-ads)
 */
internal class AdmobBannerImpl :
    AdSource.Banner<AdmobBannerAuctionParams>,
    AdLoadingType.Bidding<AdmobBannerAuctionParams>,
    AdLoadingType.Network<AdmobBannerAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    override var isAdReadyToShow: Boolean = false

    private var adSize: AdSize? = null
    private var param: AdmobBannerAuctionParams? = null
    private var adView: AdView? = null
    private val requiredAdView: AdView get() = requireNotNull(adView)

    /**
     * @see [https://developers.google.com/android/reference/com/google/android/gms/ads/OnPaidEventListener]
     */
    private val paidListener by lazy {
        OnPaidEventListener { adValue ->
            emitEvent(
                AdEvent.PaidRevenue(
                    ad = Ad(
                        demandAd = demandAd,
                        ecpm = param?.lineItem?.pricefloor ?: 0.0,
                        demandAdObject = requiredAdView,
                        networkName = demandId.demandId,
                        dsp = null,
                        roundId = roundId,
                        currencyCode = AdValue.USD,
                        auctionId = auctionId,
                        adUnitId = param?.lineItem?.adUnitId
                    ),
                    adValue = adValue.asBidonAdValue()
                )
            )
        }
    }

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        adView?.onPaidEventListener = null
        adView = null
        param = null
    }

    override fun obtainAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            AdmobBannerAuctionParams(
                lineItem = popLineItem(demandId) ?: error(BidonError.NoAppropriateAdUnitId),
                bannerFormat = bannerFormat,
                context = activity.applicationContext,
                containerWidth = containerWidth,
                payload = json?.getString("payload")
            )
        }
    }

    override fun fill(adParams: AdmobBannerAuctionParams) {
        logInfo(TAG, "Starting with $adParams")
        param = adParams
        val adRequest = AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, BidonSdk.regulation.asBundle())
            .build()
        fillBanner(adRequest)
    }

    override fun getAdView(): AdViewHolder = AdViewHolder(
        networkAdview = requiredAdView,
        widthDp = adSize?.width ?: param?.bannerFormat.getWidthDp(),
        heightDp = adSize?.height ?: param?.bannerFormat.getHeightDp()
    )

    private fun AdView.asAd(): Ad {
        return Ad(
            demandAd = demandAd,
            ecpm = param?.lineItem?.pricefloor ?: 0.0,
            demandAdObject = this,
            networkName = demandId.demandId,
            dsp = null,
            roundId = roundId,
            currencyCode = AdValue.USD,
            auctionId = auctionId,
            adUnitId = adUnitId
        )
    }

    override suspend fun getToken(context: Context): String? {
        val adRequestBuilder = AdRequest.Builder().apply {
            bindBiddingParams()
        }
        return withTimeoutOrNull(DefaultTokenTimeoutMs) {
            suspendCoroutine { continuation ->
                try {
                    QueryInfo.generate(
                        context,
                        AdFormat.BANNER,
                        adRequestBuilder.build(),
                        object : QueryInfoGenerationCallback() {
                            override fun onSuccess(queryInfo: QueryInfo) {
                                continuation.resume(queryInfo.query)
                            }

                            override fun onFailure(errorMessage: String) {
                                continuation.resumeWithException(Exception(errorMessage))
                            }
                        }
                    )
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    override fun fill() {
        val ad = getAd(this)
        if (param != null && ad != null) {
            emitEvent(AdEvent.Fill(ad))
        } else {
            emitEvent(AdEvent.ShowFailed(BidonError.BannerAdNotReady))
        }
    }

    override fun adRequest(adParams: AdmobBannerAuctionParams) {
        param = adParams

        val adRequestBuilder = AdRequest.Builder().apply {
            bindFillParams(adParams.payload, adParams.adUnitId)
        }

        fillBanner(adRequestBuilder.build())
    }

    private fun BannerFormat.asAdmobAdSize(context: Context, containerWidth: Float) = when (this) {
        BannerFormat.Banner -> AdSize.BANNER
        BannerFormat.LeaderBoard -> AdSize.LEADERBOARD
        BannerFormat.MRec -> AdSize.MEDIUM_RECTANGLE
        BannerFormat.Adaptive -> context.adaptiveAdSize(containerWidth)
    }

    @SuppressLint("MissingPermission")
    private fun fillBanner(adRequest: AdRequest) {
        val adParams = param
        val adUnitId = adParams?.lineItem?.adUnitId
        if (!adUnitId.isNullOrBlank()) {
            val requestListener = object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    logError(
                        TAG,
                        "Error while loading ad: $loadAdError. $this",
                        loadAdError.asBidonError()
                    )
                    emitEvent(
                        AdEvent.LoadFailed(loadAdError.asBidonError())
                    )
                }

                override fun onAdLoaded() {
                    logInfo(TAG, "onAdLoaded: $this")
                    adView?.run {
                        isAdReadyToShow = true
                        emitEvent(AdEvent.Fill(ad = requireNotNull(adView?.asAd())))
                    }
                }

                override fun onAdClicked() {
                    logInfo(TAG, "onAdClicked: $this")
                    emitEvent(AdEvent.Clicked(requiredAdView.asAd()))
                }

                override fun onAdClosed() {
                    logInfo(TAG, "onAdClosed: $this")
                    emitEvent(AdEvent.Closed(requiredAdView.asAd()))
                }

                override fun onAdImpression() {
                    logInfo(TAG, "onAdImpression: $this")
                    // tracked impression/shown by [BannerView]
                }

                override fun onAdOpened() {}
            }
            val adView = AdView(adParams.context)
                .apply {
                    val admobBannerSize = adParams.bannerFormat.asAdmobAdSize(
                        context = adParams.context,
                        containerWidth = adParams.containerWidth
                    )
                    this@AdmobBannerImpl.adSize = admobBannerSize
                    this.setAdSize(admobBannerSize)
                    this.adUnitId = adUnitId
                    this.adListener = requestListener
                    this.onPaidEventListener = paidListener
                }
                .also {
                    adView = it
                }
            adView.loadAd(adRequest)
        } else {
            val (error, message) = if (adParams == null) {
                BidonError.BannerAdNotReady to "Banner param is null"
            } else {
                BidonError.NoAppropriateAdUnitId to
                        "No appropriate AdUnitId found for price_floor=${param?.lineItem?.pricefloor}"
            }
            logError(tag = TAG, message = message, error = error)
            emitEvent(AdEvent.LoadFailed(error))
        }
    }
}

private const val TAG = "Admob Banner"
