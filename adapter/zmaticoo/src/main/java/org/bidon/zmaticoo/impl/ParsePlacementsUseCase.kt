package org.bidon.zmaticoo.impl

import org.bidon.zmaticoo.PlacementConfig
import org.bidon.zmaticoo.PlacementFormat
import org.json.JSONArray

/**
 * Created by Vladimir Khrolovich on 16/02/2026.
 */
internal object ParsePlacementsUseCase {
    internal operator fun invoke(placementsJson: JSONArray): List<PlacementConfig> {
        val placements = mutableListOf<PlacementConfig>()

        for (i in 0 until placementsJson.length()) {
            val obj = placementsJson.getJSONObject(i)
            val placementId = obj.getString("placement_id")
            val format = PlacementFormat.getOrNull(obj.getString("format"))
            if (format != null) {
                placements.add(PlacementConfig(placementId, format))
            }
        }

        return placements
    }
}
