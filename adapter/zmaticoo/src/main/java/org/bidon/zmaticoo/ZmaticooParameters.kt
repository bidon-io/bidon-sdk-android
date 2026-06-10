package org.bidon.zmaticoo

import org.bidon.sdk.adapter.AdapterParameters

/**
 * Created by Bidon Team on 09/01/2026.
 */
internal enum class PlacementFormat(
    val value: String
) {
    BANNER("BANNER"),
    MREC("MREC"),
    INTERSTITIAL("INTERSTITIAL"),
    REWARDED("REWARDED");

    companion object {
        fun getOrNull(value: String): PlacementFormat? = entries.firstOrNull { it.value == value }
    }
}

internal class PlacementConfig(
    val placementId: String,
    val format: PlacementFormat
)

internal class ZmaticooParameters(
    val appKey: String,
    val placements: List<PlacementConfig>
) : AdapterParameters
