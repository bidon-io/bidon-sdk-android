package org.bidon.sdk.ads.cache.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class AdCacheStrategy(
    val auctionResultStoreCapacity: Int,
    val rtbResultsStoreTtl: Long,
    val refillThreshold: Int,
    val batchSize: Int,
)

internal val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(14)

internal class AdCacheStrategyFactory {
    fun create(
        demandAd: DemandAd,
        cacheSettingsJson: JSONObject?
    ): AdCacheStrategy {
        val defaults = defaults(demandAd.adType)
        val json =
            cacheSettingsJson?.optJSONObject(demandAd.adType.jsonKey)
                ?: return defaults
        return AdCacheStrategy(
            json.optInt("adunit_cache_size", defaults.auctionResultStoreCapacity),
            json.optLong("rtb_ttl_ms", defaults.rtbResultsStoreTtl),
            json.optInt("refill_threshold", defaults.refillThreshold),
            json.optInt("adunit_batch_size", defaults.batchSize).coerceAtLeast(1)
        )
    }

    private fun defaults(adType: AdType): AdCacheStrategy =
        when (adType) {
            AdType.Banner -> AdCacheStrategy(6, DEFAULT_TTL_MS, 2, 4)
            AdType.Interstitial -> AdCacheStrategy(2, 0L, 1, 2)
            AdType.Rewarded -> AdCacheStrategy(2, DEFAULT_TTL_MS, 1, 2)
        }
}

private val AdType.jsonKey: String
    get() =
        when (this) {
            AdType.Banner -> "banner"
            AdType.Interstitial -> "interstitial"
            AdType.Rewarded -> "rewarded_video"
        }
