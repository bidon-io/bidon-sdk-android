package org.bidon.sdk.ads.cache.twolevel.config

import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.AdType
import org.json.JSONObject

internal data class TwoLevelCacheConfig(
    val mainCacheSize: Int, // default 2, range 1-10
    val fallbackCacheSize: Int, // default 1, range 0-10 (0 = disabled)
    val threshold: Int, // default 80, percentage 0-100
) {
    companion object {
        val DEFAULT = TwoLevelCacheConfig(
            mainCacheSize = 2,
            fallbackCacheSize = 1,
            threshold = 80,
        )

        fun fromExtras(adType: AdType): TwoLevelCacheConfig {
            return try {
                val cacheSettings = BidonSdk.getExtras()["cache_settings"] as? JSONObject
                    ?: return DEFAULT
                val adTypeKey = when (adType) {
                    AdType.Interstitial -> "interstitial"
                    AdType.Banner -> "banner"
                    AdType.Rewarded -> "rewarded_video"
                }
                val s = cacheSettings.optJSONObject(adTypeKey) ?: return DEFAULT
                TwoLevelCacheConfig(
                    mainCacheSize = s.optInt("adunit_cache_size", DEFAULT.mainCacheSize).coerceIn(1, 10),
                    fallbackCacheSize = s.optInt("fallback_cache_size", DEFAULT.fallbackCacheSize).coerceIn(0, 10),
                    threshold = s.optInt("threshold", DEFAULT.threshold).coerceIn(0, 100),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}
