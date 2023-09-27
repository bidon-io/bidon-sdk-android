package org.bidon.amazon.impl

import com.google.common.truth.Truth.assertThat
import org.bidon.amazon.SlotType
import org.json.JSONObject
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 27/09/2023.
 */
internal class ParseSlotsUseCaseTest {

    private val testee by lazy { ParseSlotsUseCase() }

    @Test
    internal fun testInvoke() {
        val json = """
{ 
        "app_key": "some_key",
        "slots": [
            {
                "slot_uuid": "slot_uuid_1",
                "format": "INTERSTITIAL" 
            },
            {
                "slot_uuid": "slot_uuid_10",
                "format": "MREC" 
            },
            {
                "slot_uuid": "slot_uuid_2",
                "format": "INTERSTITIAL" 
            },
            {
                "slot_uuid": "slot_uuid_3",
                "format": "BANNER" 
            }
        ]
}
        """.trimIndent()
        val result = testee(JSONObject(json))
        assertThat(result[SlotType.Banner]).isEqualTo(listOf("slot_uuid_3"))
        assertThat(result[SlotType.Interstitial]).isEqualTo(listOf("slot_uuid_1", "slot_uuid_2"))
        assertThat(result[SlotType.Mrec]).isEqualTo(listOf("slot_uuid_10"))
    }
}