package org.bidon.amazon

/**
 * Created by Aleksei Cherniaev on 27/09/2023.
 */
enum class SlotType(val format: String) {
    Banner("BANNER"),
    Interstitial("INTERSTITIAL"),
    Mrec("MREC");

    companion object {
        fun get(format: String): SlotType? = values().firstOrNull { it.format == format }
    }
}