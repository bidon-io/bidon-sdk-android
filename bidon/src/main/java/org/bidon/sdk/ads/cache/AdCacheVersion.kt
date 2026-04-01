package org.bidon.sdk.ads.cache

/**
 * Sealed interface for type-safe AdCache version handling.
 */
internal sealed interface AdCacheVersion {
    val value: Int

    data object V1 : AdCacheVersion {
        override val value = 1
    }

    data object V2 : AdCacheVersion {
        override val value = 2
    }

    companion object {
        val Default: AdCacheVersion = V1

        fun fromInt(version: Int?): AdCacheVersion = when (version) {
            1 -> V1
            2 -> V2
            else -> Default
        }

        fun fromString(version: String?): AdCacheVersion = when (version?.lowercase()) {
            "v1" -> V1
            "v2" -> V2
            else -> Default
        }
    }
}
