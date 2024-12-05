package org.bidon.sdk.utils.networking.requests

import org.bidon.sdk.databinders.DataBinderType
import org.bidon.sdk.utils.json.JsonObjectBuilder
import org.json.JSONObject

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal interface CreateRequestBodyUseCase {
    suspend operator fun invoke(
        binders: List<DataBinderType>,
        extras: Map<String, Any>,
        append: JsonObjectBuilder.() -> Unit = {},
    ): JSONObject
}
