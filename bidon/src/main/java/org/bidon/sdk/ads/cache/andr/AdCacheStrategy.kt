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
    val nofillDelayMs: Long,
)

internal val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(14)
private const val DEFAULT_NOFILL_DELAY_MS = 500L

private object BannerDefaults {
    const val CACHE_SIZE = 6
    val RTB_TTL_MS = DEFAULT_TTL_MS
    const val REFILL_THRESHOLD = 2
    const val BATCH_SIZE = 3
}

private object InterstitialDefaults {
    const val CACHE_SIZE = 2
    const val RTB_TTL_MS = 0L
    const val REFILL_THRESHOLD = 1
    const val BATCH_SIZE = 2
}

private object RewardedDefaults {
    const val CACHE_SIZE = 2
    val RTB_TTL_MS = DEFAULT_TTL_MS
    const val REFILL_THRESHOLD = 1
    const val BATCH_SIZE = 2
}

internal class AdCacheStrategyFactory {
    fun create(
        demandAd: DemandAd,
        cacheSettingsJson: JSONObject?
    ): AdCacheStrategy {
        val adTypeKey =
            when (demandAd.adType) {
                AdType.Banner -> "banner"
                AdType.Interstitial -> "interstitial"
                AdType.Rewarded -> "rewarded_video"
            }
        val json = cacheSettingsJson?.optJSONObject(adTypeKey)

        return when (demandAd.adType) {
            AdType.Banner -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity =
                        json?.optInt(
                            "adunit_cache_size",
                            BannerDefaults.CACHE_SIZE
                        ) ?: BannerDefaults.CACHE_SIZE,
                    rtbResultsStoreTtl =
                        json?.optLong("rtb_ttl_ms", BannerDefaults.RTB_TTL_MS)
                            ?: BannerDefaults.RTB_TTL_MS,
                    refillThreshold =
                        json?.optInt("refill_threshold", BannerDefaults.REFILL_THRESHOLD)
                            ?: BannerDefaults.REFILL_THRESHOLD,
                    batchSize =
                        json?.optInt("adunit_batch_size", BannerDefaults.BATCH_SIZE)
                            ?: BannerDefaults.BATCH_SIZE,
                    nofillDelayMs =
                        json?.optLong("nofill_delay_ms", DEFAULT_NOFILL_DELAY_MS)
                            ?: DEFAULT_NOFILL_DELAY_MS,
                )
            }

            AdType.Interstitial -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity =
                        json?.optInt(
                            "adunit_cache_size",
                            InterstitialDefaults.CACHE_SIZE
                        ) ?: InterstitialDefaults.CACHE_SIZE,
                    rtbResultsStoreTtl =
                        json?.optLong("rtb_ttl_ms", InterstitialDefaults.RTB_TTL_MS)
                            ?: InterstitialDefaults.RTB_TTL_MS,
                    refillThreshold =
                        json?.optInt(
                            "refill_threshold",
                            InterstitialDefaults.REFILL_THRESHOLD
                        ) ?: InterstitialDefaults.REFILL_THRESHOLD,
                    batchSize =
                        json?.optInt("adunit_batch_size", InterstitialDefaults.BATCH_SIZE)
                            ?: InterstitialDefaults.BATCH_SIZE,
                    nofillDelayMs =
                        json?.optLong("nofill_delay_ms", DEFAULT_NOFILL_DELAY_MS)
                            ?: DEFAULT_NOFILL_DELAY_MS,
                )
            }

            AdType.Rewarded -> {
                AdCacheStrategy(
                    auctionResultStoreCapacity =
                        json?.optInt(
                            "adunit_cache_size",
                            RewardedDefaults.CACHE_SIZE
                        ) ?: RewardedDefaults.CACHE_SIZE,
                    rtbResultsStoreTtl =
                        json?.optLong("rtb_ttl_ms", RewardedDefaults.RTB_TTL_MS)
                            ?: RewardedDefaults.RTB_TTL_MS,
                    refillThreshold =
                        json?.optInt(
                            "refill_threshold",
                            RewardedDefaults.REFILL_THRESHOLD
                        ) ?: RewardedDefaults.REFILL_THRESHOLD,
                    batchSize =
                        json?.optInt("adunit_batch_size", RewardedDefaults.BATCH_SIZE)
                            ?: RewardedDefaults.BATCH_SIZE,
                    nofillDelayMs =
                        json?.optLong("nofill_delay_ms", DEFAULT_NOFILL_DELAY_MS)
                            ?: DEFAULT_NOFILL_DELAY_MS,
                )
            }
        }
    }
}
