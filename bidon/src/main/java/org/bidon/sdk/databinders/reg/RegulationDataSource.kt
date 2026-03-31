package org.bidon.sdk.databinders.reg

import org.bidon.sdk.config.models.RegulationsRequestBody
import org.bidon.sdk.databinders.DataSource

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal interface RegulationDataSource : DataSource {
    val regulationsRequestBody: RegulationsRequestBody
}
