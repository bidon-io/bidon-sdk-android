package org.bidon.admob.impl

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.*
import com.google.android.gms.ads.mediation.rtb.RtbAdapter
import com.google.android.gms.ads.query.QueryInfo
import com.google.android.gms.ads.query.QueryInfoGenerationCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.runBlocking
import org.bidon.admob.AdmobFullscreenAdAuctionParams
import org.bidon.admob.asBidonError
import org.bidon.admob.ext.asBidonAdValue
import org.bidon.admob.ext.asBundle
import org.bidon.admob.ext.getDefaultBiddingParams
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.*
import org.bidon.sdk.adapter.impl.AdEventFlow
import org.bidon.sdk.adapter.impl.AdEventFlowImpl
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.StatisticsCollectorImpl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// $0.1 ca-app-pub-9630071911882835/9299488830
// $0.5 ca-app-pub-9630071911882835/4234864416
// $1.0 ca-app-pub-9630071911882835/7790966049
// $2.0 ca-app-pub-9630071911882835/1445049547

@Suppress("unused")
internal class AdmobRewardedImpl :
    AdSource.Rewarded<AdmobFullscreenAdAuctionParams>,
    AdLoadingType.Bidding<AdmobFullscreenAdAuctionParams>,
    AdLoadingType.Network<AdmobFullscreenAdAuctionParams>,
    AdEventFlow by AdEventFlowImpl(),
    StatisticsCollector by StatisticsCollectorImpl() {

    private var _context: Context? = null
    private val context: Context = requireNotNull(_context)
    private var param: AdmobFullscreenAdAuctionParams? = null
    private var rewardedAd: RewardedAd? = null
    private val requiredRewardedAd: RewardedAd get() = requireNotNull(rewardedAd)
    private val adRequestBuilder: AdRequest.Builder by lazy { AdRequest.Builder() }

    private val onUserEarnedRewardListener by lazy {
        OnUserEarnedRewardListener { rewardItem ->
            logInfo(TAG, "onUserEarnedReward $rewardItem: $this")
            emitEvent(
                AdEvent.OnReward(
                    ad = requiredRewardedAd.asAd(),
                    reward = Reward(rewardItem.type, rewardItem.amount)
                )
            )
            sendRewardImpression()
        }
    }

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
                        demandAdObject = requiredRewardedAd,
                        networkName = demandId.demandId,
                        dsp = null,
                        roundId = roundId,
                        currencyCode = "USD",
                        auctionId = auctionId,
                        adUnitId = param?.lineItem?.adUnitId
                    ),
                    adValue = adValue.asBidonAdValue()
                )
            )
        }
    }

    private val rewardedListener by lazy {
        object : FullScreenContentCallback() {
            override fun onAdClicked() {
                logInfo(TAG, "onAdClicked: $this")
                emitEvent(AdEvent.Clicked(requiredRewardedAd.asAd()))
            }

            override fun onAdDismissedFullScreenContent() {
                logInfo(TAG, "onAdDismissedFullScreenContent: $this")
                emitEvent(AdEvent.Closed(requiredRewardedAd.asAd()))
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                logError(TAG, "onAdFailedToShowFullScreenContent: $this", error.asBidonError())
                emitEvent(AdEvent.ShowFailed(error.asBidonError()))
            }

            override fun onAdImpression() {
                logInfo(TAG, "onAdShown: $this")
                emitEvent(AdEvent.Shown(requiredRewardedAd.asAd()))
            }

            override fun onAdShowedFullScreenContent() {}
        }
    }

    override val isAdReadyToShow: Boolean
        get() = rewardedAd != null

    override fun destroy() {
        logInfo(TAG, "destroy $this")
        rewardedAd?.onPaidEventListener = null
        rewardedAd?.fullScreenContentCallback = null
        rewardedAd = null
        param = null
    }

    override fun obtainAuctionParam(auctionParamsScope: AdAuctionParamSource): Result<AdAuctionParams> {
        return auctionParamsScope {
            AdmobFullscreenAdAuctionParams(
                lineItem = popLineItem(demandId) ?: error(BidonError.NoAppropriateAdUnitId),
                context = activity.applicationContext,
                payload = requireNotNull(json?.getString("payload")) {
                    "Payload is required for GoogleBidding"
                },
            )
        }
    }

    override fun fill(adParams: AdmobFullscreenAdAuctionParams) {
        logInfo(TAG, "Starting with $adParams: $this")
        param = adParams
        val adRequest = AdRequest.Builder()
            .addNetworkExtrasBundle(AdMobAdapter::class.java, BidonSdk.regulation.asBundle())
            .build()
        fillRewarded(adRequest)
    }

    override fun show(activity: Activity) {
        logInfo(TAG, "Starting show: $this")
        if (rewardedAd == null) {
            emitEvent(AdEvent.ShowFailed(BidonError.FullscreenAdNotReady))
        } else {
            rewardedAd?.show(activity, onUserEarnedRewardListener)
        }
    }

    override fun getToken(context: Context): String {
        _context = context
        return runBlocking {
            val query: String? = try {
                suspendCoroutine { continuation ->
                    QueryInfo.generate(
                        context,
                        AdFormat.BANNER,
                        null,
                        object : QueryInfoGenerationCallback() {
                            override fun onSuccess(queryInfo: QueryInfo) {
                                continuation.resume(queryInfo.query)
                            }

                            override fun onFailure(errorMessage: String) {
                                continuation.resumeWithException(Exception(errorMessage))
                            }
                        }
                    )
                }
            } catch (e: Exception) {

                null
            }
            query ?: ""
        }
    }

    override fun fill() {
        fillRewarded(adRequestBuilder.build())
    }

    override fun adRequest(adParams: AdmobFullscreenAdAuctionParams) {
        param = adParams
        _context = adParams.context

        val networkExtras = Bundle().apply {
            putAll(getDefaultBiddingParams())
            putString("placement_req_id", adParams.adUnitId)
        }

        val bidResponse = adParams.payload
        adRequestBuilder.setAdString(bidResponse)
        adRequestBuilder.setRequestAgent("bidon")

        adRequestBuilder.addNetworkExtrasBundle(RtbAdapter::class.java, networkExtras)
    }

    private fun RewardedAd.asAd(): Ad {
        return Ad(
            demandAd = demandAd,
            ecpm = param?.lineItem?.pricefloor ?: 0.0,
            demandAdObject = this,
            networkName = demandId.demandId,
            dsp = null,
            roundId = roundId,
            currencyCode = "USD",
            auctionId = auctionId,
            adUnitId = param?.lineItem?.adUnitId
        )
    }

    private fun fillRewarded(adRequest: AdRequest) {
        val adUnitId = param?.lineItem?.adUnitId
        if (!adUnitId.isNullOrBlank()) {
            val requestListener = object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    logError(
                        TAG,
                        "Error while loading ad. LoadAdError=$loadAdError.\n$this",
                        loadAdError.asBidonError()
                    )
                    emitEvent(AdEvent.LoadFailed(loadAdError.asBidonError()))
                }

                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    logInfo(TAG, "onAdLoaded. RewardedAd=$rewardedAd, $this")
                    this@AdmobRewardedImpl.rewardedAd = rewardedAd
                    requiredRewardedAd.onPaidEventListener = paidListener
                    requiredRewardedAd.fullScreenContentCallback = rewardedListener
                    emitEvent(AdEvent.Fill(requiredRewardedAd.asAd()))
                }
            }
            RewardedAd.load(context, adUnitId, adRequest, requestListener)
        } else {
            val error = BidonError.NoAppropriateAdUnitId
            logError(
                tag = TAG,
                message = "No appropriate AdUnitId found for price_floor=${param?.lineItem?.pricefloor}",
                error = error
            )
            emitEvent(AdEvent.LoadFailed(error))
        }
    }
}

private const val TAG = "Admob Rewarded"
