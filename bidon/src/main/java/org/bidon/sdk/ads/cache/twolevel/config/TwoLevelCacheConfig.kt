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
        val DEFAULT_FULLSCREEN = TwoLevelCacheConfig(
            mainCacheSize = 2,
            fallbackCacheSize = 1,
            threshold = 80,
        )

        val DEFAULT_BANNER = TwoLevelCacheConfig(
            mainCacheSize = 3,
            fallbackCacheSize = 5,
            threshold = 70,
        )

        fun fromExtras(adType: AdType): TwoLevelCacheConfig {
            val default = defaultFor(adType)
            return try {
                val cacheSettings = BidonSdk.getExtras()["cache_settings"] as? JSONObject
                    ?: return default
                val adTypeKey = when (adType) {
                    AdType.Interstitial -> "interstitial"
                    AdType.Banner -> "banner"
                    AdType.Rewarded -> "rewarded_video"
                }
                val s = cacheSettings.optJSONObject(adTypeKey) ?: return default
                TwoLevelCacheConfig(
                    mainCacheSize = s.optInt("adunit_cache_size", default.mainCacheSize).coerceIn(1, 10),
                    fallbackCacheSize = s.optInt("fallback_cache_size", default.fallbackCacheSize).coerceIn(0, 10),
                    threshold = s.optInt("threshold", default.threshold).coerceIn(0, 100),
                )
            } catch (_: Exception) {
                default
            }
        }

        private fun defaultFor(adType: AdType): TwoLevelCacheConfig = when (adType) {
            AdType.Banner -> DEFAULT_BANNER
            AdType.Interstitial, AdType.Rewarded -> DEFAULT_FULLSCREEN
        }
    }
}
