package org.bidon.sdk.auction.impl

import kotlinx.coroutines.withContext
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.banner.helper.GetOrientationUseCase
import org.bidon.sdk.ads.cache.AdCacheProvider
import org.bidon.sdk.ads.ext.asAdRequestBody
import org.bidon.sdk.ads.ext.asAdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdCacheRequest
import org.bidon.sdk.auction.models.AdObjectRequest
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.databinders.DataBinderType
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.segment.SegmentSynchronizer
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.json.JsonParsers
import org.bidon.sdk.utils.networking.JsonHttpRequest
import org.bidon.sdk.utils.networking.requests.CreateRequestBodyUseCase
import org.bidon.sdk.utils.serializer.serialize

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal class GetAuctionRequestUseCaseImpl(
    private val createRequestBody: CreateRequestBodyUseCase,
    private val getOrientation: GetOrientationUseCase,
    private val segmentSynchronizer: SegmentSynchronizer,
    private val adCacheProvider: AdCacheProvider,
) : GetAuctionRequestUseCase {
    private val binders: List<DataBinderType> = listOf(
        DataBinderType.AvailableAdapters,
        DataBinderType.Device,
        DataBinderType.App,
        DataBinderType.Token,
        DataBinderType.Session,
        DataBinderType.User,
        DataBinderType.Segment,
        DataBinderType.Reg,
        DataBinderType.Test,
    )

    override suspend fun request(
        adTypeParam: AdTypeParam,
        auctionId: String,
        demandAd: DemandAd,
        demandsTokens: Map<String, TokenInfo>,
    ): Result<AuctionResponse> {
        return withContext(SdkDispatchers.IO) {
            val adObject = createAdObjectRequestBody(adTypeParam, auctionId, demandsTokens)
            val adCache = createAdCacheRequestBody(demandAd, adTypeParam)

            val requestBody = createRequestBody(
                binders = binders,
                extras = BidonSdk.getExtras() + demandAd.getExtras()
            ) {
                "ad_object" hasValue adObject.serialize()
                "ad_cache" hasValue adCache.serialize()
            }
            logInfo(TAG, "Request body: $requestBody")
            get<JsonHttpRequest>().invoke(
                path = "$AuctionRequestPath/${adTypeParam.asAdType().code}",
                body = requestBody,
            ).mapCatching { jsonResponse ->
                segmentSynchronizer.parseSegmentUid(jsonResponse)
                requireNotNull(JsonParsers.parseOrNull<AuctionResponse>(jsonResponse))
            }.onFailure {
                logError(TAG, "Error while loading auction data", it)
            }.onSuccess {
                logInfo(TAG, "Loaded auction data: $it")
            }
        }
    }

    private fun createAdObjectRequestBody(
        adTypeParam: AdTypeParam,
        auctionId: String,
        demandsTokens: Map<String, TokenInfo>
    ): AdObjectRequest {
        val (banner, interstitial, rewarded) = adTypeParam.asAdRequestBody()
        return AdObjectRequest(
            auctionId = auctionId,
            auctionKey = adTypeParam.auctionKey,
            banner = banner,
            interstitial = interstitial,
            rewarded = rewarded,
            orientationCode = getOrientation().code,
            pricefloor = adTypeParam.pricefloor,
            demands = demandsTokens,
        )
    }

    private fun createAdCacheRequestBody(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam
    ): List<AdCacheRequest> {
        val adCache = when (adTypeParam) {
            is AdTypeParam.Banner -> adCacheProvider.provide(demandAd, adTypeParam.bannerFormat)
            is AdTypeParam.Interstitial -> adCacheProvider.provide(demandAd)
            is AdTypeParam.Rewarded -> adCacheProvider.provide(demandAd)
        }
        return adCache.all().map { adSource ->
            val stats = adSource.getStats()
            AdCacheRequest(
                demandId = stats.demandId.demandId,
                price = stats.ecpm,
                timestamp = stats.fillFinishTs,
                uid = stats.adUnit?.uid,
            )
        }
    }
}

private const val AuctionRequestPath = "auction"
private const val TAG = "AuctionRequestUseCase"
