package org.bidon.amazon.ext

import com.amazon.device.ads.AdRegistration
import org.bidon.amazon.BuildConfig
import org.bidon.amazon.SlotType
import org.json.JSONArray

internal var adapterVersion = BuildConfig.ADAPTER_VERSION
internal var sdkVersion = AdRegistration.getVersion()

internal fun JSONArray.toSlots(): Map<SlotType, List<String>> {
    val slots = mutableMapOf<SlotType, MutableList<String>>()
    for (i in 0 until length()) {
        val slotObject = getJSONObject(i)
        val slotUuid = slotObject.getString("slot_uuid")
        SlotType.getOrNull(slotObject.getString("format"))?.let { slotType ->
            val slotList = slots.getOrPut(slotType) { mutableListOf() }
            slotList.add(slotUuid)
        }
    }
    return slots
}

