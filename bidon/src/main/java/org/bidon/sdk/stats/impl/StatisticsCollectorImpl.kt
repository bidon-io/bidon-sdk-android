package org.bidon.sdk.stats.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.InterstitialRequest
import org.bidon.sdk.auction.models.RewardedRequest
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidStat
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.ImpressionRequestBody
import org.bidon.sdk.stats.models.DemandStatus
import org.bidon.sdk.stats.usecases.SendImpressionRequestUseCase
import org.bidon.sdk.stats.usecases.SendWinLossRequestUseCase
import org.bidon.sdk.stats.usecases.WinLossRequestData
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Bidon Team on 06/02/2023.
 */
class StatisticsCollectorImpl : StatisticsCollector {

    private var auctionConfigurationId: Long = 0L
    private var auctionConfigurationUid: String = ""
    private var externalWinNotificationsEnabled: Boolean = true

    private val sendImpression by lazy {
        get<SendImpressionRequestUseCase>()
    }
    private val sendLossRequest by lazy {
        get<SendWinLossRequestUseCase>()
    }

    private val isShowSent = AtomicBoolean(false)
    private val isWinLossSent = AtomicBoolean(false)
    private val isClickSent = AtomicBoolean(false)
    private val isRewardSent = AtomicBoolean(false)
    private val scope by lazy {
        CoroutineScope(SdkDispatchers.IO)
    }

    private var stat: BidStat = BidStat(
        auctionId = null,
        demandId = DemandId(""),
        demandStatus = null,
        ecpm = 0.0,
        auctionPricefloor = 0.0,
        fillStartTs = null,
        fillFinishTs = null,
        dsp = null,
        adUnit = null,
    )

    private var _demandAd: DemandAd? = null
    override val demandAd: DemandAd
        get() = requireNotNull(_demandAd) { "DemandAd is not set" }

    private var _adType: StatisticsCollector.AdType? = null
    private val adType: StatisticsCollector.AdType
        get() = requireNotNull(_adType) { "AdType is not set" }

    override val demandId: DemandId
        get() = requireNotNull(stat.demandId) { "DemandId is not set" }
    override val auctionId: String
        get() = requireNotNull(stat.auctionId) { "AuctionId is not set" }

    override fun getAd(): Ad? {
        val auctionId = stat.auctionId
        val bidType = stat.bidType
        val adUnit = stat.adUnit
        if (adUnit == null || auctionId == null || bidType == null) {
            logError(TAG, "Ad is null", NullPointerException())
            return null
        }
        return Ad(
            demandAd = demandAd,
            ecpm = stat.ecpm,
            currencyCode = AdValue.USD,
            auctionId = auctionId,
            dsp = stat.dsp,
            adUnit = adUnit
        )
    }

    override fun addDemandId(demandId: DemandId) {
        stat = stat.copy(
            demandId = demandId
        )
    }

    override fun addAuctionInfo(
        auctionId: String,
        auctionPricefloor: Double,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String
    ) {
        // TODO: 25/10/2024 [glavatskikh] add auctionConfigurationId and auctionConfigurationUid to stat
        stat = stat.copy(
            auctionId = auctionId,
            auctionPricefloor = auctionPricefloor
        )
    }

    override fun sendShowImpression() {
        if (!isShowSent.getAndSet(true)) {
            scope.launch {
                val key = SendImpressionRequestUseCase.Type.Show.key
                val lastSegment = adType.asAdType().code
                sendImpression(
                    urlPath = "$key/$lastSegment",
                    bodyKey = "bid",
                    body = createImpressionRequestBody(adType),
                    extras = demandAd.getExtras()
                )
            }
        }
    }

    override fun sendClickImpression() {
        if (!isClickSent.getAndSet(true)) {
            scope.launch {
                val key = SendImpressionRequestUseCase.Type.Click.key
                val lastSegment = adType.asAdType().code
                sendImpression(
                    urlPath = "$key/$lastSegment",
                    bodyKey = "bid",
                    body = createImpressionRequestBody(adType),
                    extras = demandAd.getExtras()
                )
            }
        }
    }

    override fun sendRewardImpression() {
        if (!isRewardSent.getAndSet(true)) {
            scope.launch {
                val key = SendImpressionRequestUseCase.Type.Reward.key
                val lastSegment = StatisticsCollector.AdType.Rewarded.asAdType().code
                sendImpression(
                    urlPath = "$key/$lastSegment",
                    bodyKey = "bid",
                    body = createImpressionRequestBody(StatisticsCollector.AdType.Rewarded),
                    extras = demandAd.getExtras()
                )
            }
        }
    }

    override fun sendLoss(winnerDemandId: String, winnerEcpm: Double) {
        if (!externalWinNotificationsEnabled) {
            logInfo(
                TAG,
                "External WinLoss Notifications disabled: external_win_notifications=false"
            )
            return
        }
        if (!isShowSent.getAndSet(true) && !isWinLossSent.getAndSet(true)) {
            scope.launch {
                sendLossRequest.invoke(
                    WinLossRequestData.Loss(
                        winnerDemandId = winnerDemandId,
                        winnerEcpm = winnerEcpm,
                        demandAd = demandAd,
                        body = createImpressionRequestBody(adType)
                    )
                )
            }
        }
    }

    override fun sendWin() {
        if (!externalWinNotificationsEnabled) {
            logInfo(
                TAG,
                "External WinLoss Notifications disabled: external_win_notifications=false"
            )
            return
        }
        if (!isShowSent.get() && !isWinLossSent.getAndSet(true)) {
            scope.launch {
                sendLossRequest.invoke(
                    WinLossRequestData.Win(
                        demandAd = demandAd,
                        body = createImpressionRequestBody(adType)
                    )
                )
            }
        }
    }

    override fun setDemandAd(demandAd: DemandAd) {
        this._demandAd = demandAd
    }

    override fun setStatisticAdType(adType: StatisticsCollector.AdType) {
        this._adType = adType
    }

    override fun setAuctionConfigurationId(auctionConfigurationId: Long) {
        this.auctionConfigurationId = auctionConfigurationId
    }

    override fun setAuctionConfigurationUid(auctionConfigurationUid: String) {
        this.auctionConfigurationUid = auctionConfigurationUid
    }

    override fun setExternalWinNotificationsEnabled(enabled: Boolean) {
        externalWinNotificationsEnabled = enabled
    }

    override fun markFillStarted(adUnit: AdUnit, pricefloor: Double?) {
        stat = stat.copy(
            fillStartTs = SystemTimeNow,
            adUnit = adUnit,
            ecpm = pricefloor ?: stat.ecpm,
        )
    }

    override fun markFillFinished(demandStatus: DemandStatus, ecpm: Double?) {
        stat = stat.copy(
            fillFinishTs = SystemTimeNow,
            demandStatus = demandStatus,
            ecpm = ecpm ?: 0.0
        )
    }

    override fun setEcpm(ecpm: Double) {
        stat = stat.copy(
            ecpm = ecpm
        )
    }

    override fun setDsp(dsp: String?) {
        stat = stat.copy(
            dsp = dsp
        )
    }

    override fun markWin() {
        stat = stat.copy(
            demandStatus = DemandStatus.Win
        )
    }

    override fun markLoss() {
        stat = stat.copy(
            demandStatus = DemandStatus.Lose
        )
    }

    override fun markBelowPricefloor() {
        stat = stat.copy(
            demandStatus = if (stat.adUnit?.bidType == BidType.RTB) DemandStatus.Lose
            else DemandStatus.BelowPricefloor
        )
    }

    override fun getStats(): BidStat = stat

    private fun createImpressionRequestBody(adType: StatisticsCollector.AdType): ImpressionRequestBody {
        val (banner, interstitial, rewarded) = getAdRequestBody(adType)
        return ImpressionRequestBody(
            auctionId = auctionId,
            auctionConfigurationId = auctionConfigurationId,
            auctionConfigurationUid = auctionConfigurationUid,
            demandId = demandId.demandId,
            price = stat.ecpm,
            banner = banner,
            interstitial = interstitial,
            rewarded = rewarded,
            bidType = stat.bidType?.code,
            adUnitLabel = stat.adUnit?.label,
            adUnitUid = stat.adUnit?.uid,
            auctionPricefloor = stat.auctionPricefloor,
        )
    }

    private fun getAdRequestBody(adType: StatisticsCollector.AdType): Triple<BannerRequest?, InterstitialRequest?, RewardedRequest?> {
        return when (adType) {
            is StatisticsCollector.AdType.Banner -> {
                Triple(BannerRequest(formatCode = adType.format.code), null, null)
            }

            StatisticsCollector.AdType.Interstitial -> {
                Triple(null, InterstitialRequest, null)
            }

            StatisticsCollector.AdType.Rewarded -> {
                Triple(null, null, RewardedRequest)
            }
        }
    }

    private fun StatisticsCollector.AdType.asAdType() = when (this) {
        is StatisticsCollector.AdType.Banner -> AdType.Banner
        StatisticsCollector.AdType.Interstitial -> AdType.Interstitial
        StatisticsCollector.AdType.Rewarded -> AdType.Rewarded
    }
}

private const val TAG = "StatisticsCollector"