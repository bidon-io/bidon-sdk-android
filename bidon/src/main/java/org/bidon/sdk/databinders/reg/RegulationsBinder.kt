package org.bidon.sdk.databinders.reg

import org.bidon.sdk.databinders.DataBinder
import org.bidon.sdk.utils.serializer.serialize
import org.json.JSONObject

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal class RegulationsBinder(
    private val dataSource: RegulationDataSource
) : DataBinder<JSONObject> {
    override val fieldName: String
        get() = "regs"

    override suspend fun getJsonObject(): JSONObject {
        return dataSource.regulationsRequestBody.serialize()
    }
}