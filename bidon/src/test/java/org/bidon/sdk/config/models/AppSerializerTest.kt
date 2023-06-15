package org.bidon.sdk.config.models

import org.bidon.sdk.config.models.json_scheme_utils.assertEquals
import org.bidon.sdk.config.models.json_scheme_utils.expectedJsonStructure
import org.bidon.sdk.utils.json.jsonObject
import org.bidon.sdk.utils.serializer.serialize
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 24/02/2023.
 */
internal class AppSerializerTest {

    @Test
    fun `sdf`() {

        println(
            jsonObject {
                "segment" hasValue jsonObject {
                    "id" hasValue "2020327"
                    "ext" hasValue jsonObject {
                        "age" hasValue 23
                        "gender" hasValue "male"
                        "custom_attributes" hasValue mapOf(
                            "some_prop" to "super",
                            "int_value" to 100500
                        )
                        "total_in_apps_amount" hasValue 12
                        "is_paying" hasValue true
                        "game_level" hasValue 300
                    }.toString()
                }
            }
        )

    }
    @Test
    fun `App serializer`() {
        val actual = App(
            bundle = "bndl",
            key = "asd",
            framework = "frm12",
            version = "123",
            frameworkVersion = "4546",
            pluginVersion = "97",
        ).serialize()

        actual.assertEquals(
            expectedJsonStructure {
                "bundle" hasValue "bndl"
                "key" hasValue "asd"
                "framework" hasValue "frm12"
                "version" hasValue "123"
                "framework_version" hasValue "4546"
                "plugin_version" hasValue "97"
            }
        )
    }
    @Test
    fun `App serializer with optional`() {
        val actual = App(
            bundle = "bndl",
            key = null,
            framework = "frm12",
            version = null,
            frameworkVersion = null,
            pluginVersion = null,
        ).serialize()

        actual.assertEquals(
            expectedJsonStructure {
                "bundle" hasValue "bndl"
                "framework" hasValue "frm12"
            }
        )
    }
}