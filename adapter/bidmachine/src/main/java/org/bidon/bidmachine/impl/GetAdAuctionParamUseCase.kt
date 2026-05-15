package org.bidon.bidmachine.impl

import io.bidmachine.CustomParams
import io.bidmachine.TargetingParams
import org.bidon.bidmachine.BMBannerAuctionParams
import org.bidon.bidmachine.BMFullscreenAuctionParams
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.json.JSONObject

/**
 * Created by Bidon Team on 27/11/2023.
 */
internal class GetAdAuctionParamUseCase(
    internal val mediationMode: String = "bidon",
) {
    fun getBMFullscreenAuctionParams(auctionParamsScope: AdAuctionParamSource): Result<BMFullscreenAuctionParams> {
        return auctionParamsScope {
            val extra = adUnit.extra
            BMFullscreenAuctionParams(
                price = adUnit.pricefloor,
                timeout = adUnit.timeout,
                context = activity.applicationContext,
                adUnit = adUnit,
                customParameters = extra.buildCustomParameters(),
                targetingParams = extra.buildTargetingParams(),
                payload = extra?.optString("payload"),
                placement = extra?.optString("placement"),
            )
        }
    }

    fun getBMBannerAuctionParams(auctionParamsScope: AdAuctionParamSource): Result<BMBannerAuctionParams> {
        return auctionParamsScope {
            val extra = adUnit.extra
            BMBannerAuctionParams(
                price = adUnit.pricefloor,
                timeout = adUnit.timeout,
                activity = activity,
                bannerFormat = bannerFormat,
                adUnit = adUnit,
                customParameters = extra.buildCustomParameters(),
                targetingParams = extra.buildTargetingParams(),
                payload = extra?.optString("payload"),
                placement = extra?.optString("placement"),
            )
        }
    }

    private fun JSONObject?.buildTargetingParams(): TargetingParams {
        return TargetingParams().apply {
            this@buildTargetingParams?.toList("bcat")?.forEach { category ->
                addBlockedAdvertiserIABCategory(category)
            }
            this@buildTargetingParams?.toList("badv")?.forEach { domain ->
                addBlockedAdvertiserDomain(domain)
            }
            this@buildTargetingParams?.toList("bapps")?.forEach { app ->
                addBlockedApplication(app)
            }
        }
    }

    private fun JSONObject?.buildCustomParameters(): CustomParams {
        return CustomParams().apply {
            addParam("mediation_mode", mediationMode)

            this@buildCustomParameters?.optJSONObject("custom_parameters")?.let { paramsJson ->
                paramsJson.keys().forEach { key ->
                    paramsJson.optString(key).takeIf { it.isNotEmpty() }?.let { value ->
                        addParam(key, value)
                    }
                }
            }
        }
    }

    private fun JSONObject.toList(key: String): List<String>? {
        return optJSONArray(key)?.let { jsonArray ->
            buildList {
                repeat(jsonArray.length()) { index ->
                    jsonArray.optString(index).takeIf { it.isNotEmpty() }?.let { value ->
                        add(value)
                    }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}