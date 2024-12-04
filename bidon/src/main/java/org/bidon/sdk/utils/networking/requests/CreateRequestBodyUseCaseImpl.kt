package org.bidon.sdk.utils.networking.requests

import org.bidon.sdk.databinders.DataBinderType
import org.bidon.sdk.databinders.DataProvider
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.json.JsonObjectBuilder
import org.bidon.sdk.utils.json.jsonObject
import org.json.JSONObject

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal class CreateRequestBodyUseCaseImpl(
    private val dataProvider: DataProvider,
) : CreateRequestBodyUseCase {
    override suspend fun invoke(
        binders: List<DataBinderType>,
        extras: Map<String, Any>,
        append: JsonObjectBuilder.() -> Unit
    ): JSONObject {
        val bindData = binders
            .takeIf { it.isNotEmpty() }
            ?.let { dataProvider.provide(binders) }
        return jsonObject {
            bindData?.forEach { (key, jsonElement) ->
                key hasValue jsonElement
            }
            if (extras.isNotEmpty()) {
                "ext" hasValue JSONObject(extras).toString()
            }
            append(this)
        }.also {
            logInfo(TAG, "$it")
        }
    }
}

private const val TAG = "CreateRequestBodyUseCase"
