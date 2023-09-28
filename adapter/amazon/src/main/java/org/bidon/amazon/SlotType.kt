package org.bidon.amazon

/**
 * Created by Aleksei Cherniaev on 27/09/2023.
 */
enum class SlotType(val format: String) {
    BANNER("BANNER"),
    LEADER_BOARD("LEADER_BOARD"),
    INTERSTITIAL("INTERSTITIAL"),
    MREC("MREC");

    companion object {
        fun get(format: String): SlotType? = values().firstOrNull { it.format == format }
    }
}