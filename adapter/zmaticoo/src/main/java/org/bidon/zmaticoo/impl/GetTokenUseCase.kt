package org.bidon.zmaticoo.impl

import com.zmaticoo.sdk.base.common.ZMaticooAds
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.zmaticoo.PlacementConfig
import org.bidon.zmaticoo.PlacementFormat
import org.json.JSONObject

/**
 * Created by Bidon Team on 16/02/2026.
 */
internal object GetTokenUseCase {
    operator fun invoke(
        adTypeParam: AdTypeParam,
        placements: List<PlacementConfig>
    ): String? {
        val tokensMap = mutableMapOf<String, JSONObject>()

        val targetFormat = when (adTypeParam) {
            is AdTypeParam.Banner -> if (adTypeParam.bannerFormat == BannerFormat.MRec) {
                PlacementFormat.MREC
            } else {
                PlacementFormat.BANNER
            }
            is AdTypeParam.Interstitial -> PlacementFormat.INTERSTITIAL
            is AdTypeParam.Rewarded -> PlacementFormat.REWARDED
        }

        for (placement in placements.filter { it.format == targetFormat }) {
            val timestamp = System.currentTimeMillis()
            val token = ZMaticooAds.getBiddingToken(placement.placementId, timestamp)
            if (!token.isNullOrEmpty()) {
                tokensMap[placement.placementId] =
                    JSONObject().apply {
                        put("token", token)
                        put("timestamp", timestamp)
                    }
            }
        }

        return if (tokensMap.isNotEmpty()) {
            logInfo(TAG, "getToken: tokensMap $tokensMap")
            JSONObject(tokensMap).toString()
        } else {
            null
        }
    }
}

private const val TAG = "ZmaticooGetTokenUseCase"
