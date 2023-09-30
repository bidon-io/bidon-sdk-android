package org.bidon.sdk.config.impl

import kotlinx.coroutines.withContext
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.config.models.ConfigRequestBody
import org.bidon.sdk.config.models.ConfigResponse
import org.bidon.sdk.config.usecases.GetConfigRequestUseCase
import org.bidon.sdk.databinders.DataBinderType
import org.bidon.sdk.segment.SegmentSynchronizer
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.json.JsonParsers
import org.bidon.sdk.utils.json.jsonArray
import org.bidon.sdk.utils.json.jsonObject
import org.bidon.sdk.utils.networking.JsonHttpRequest
import org.bidon.sdk.utils.networking.requests.CreateRequestBodyUseCase
import org.bidon.sdk.utils.serializer.serialize
import org.json.JSONObject

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal class GetConfigRequestUseCaseImpl(
    private val createRequestBody: CreateRequestBodyUseCase,
    private val segmentSynchronizer: SegmentSynchronizer,
) : GetConfigRequestUseCase {
    private val binders: List<DataBinderType> = listOf(
        DataBinderType.Device,
        DataBinderType.App,
        DataBinderType.Token,
        DataBinderType.Session,
        DataBinderType.User,
        DataBinderType.Reg,
        DataBinderType.Test,
        DataBinderType.Segment,
    )

    override suspend fun request(body: ConfigRequestBody): Result<ConfigResponse> {
        return withContext(SdkDispatchers.IO) {
            val bindersData = createRequestBody(
                binders = binders,
                dataKeyName = null,
                data = null,
                extras = BidonSdk.getExtras()
            )
            val requestBody = jsonObject(putTo = bindersData) {
                "adapters" hasValue jsonObject {
                    body.adapters.forEach { (adapterName, data) ->
                        adapterName hasValue data.serialize()
                    }
                }
            }
            get<JsonHttpRequest>().invoke(
                path = ConfigRequestPath,
                body = requestBody,
            ).mapCatching { jsonString ->
                /**
                 * Save "segment_id"
                 */
                val jsonResponse = JSONObject(jsonString)
                segmentSynchronizer.parseSegmentId(jsonString)
                val config = jsonResponse.getString("init")
                val a = requireNotNull(JsonParsers.parseOrNull<ConfigResponse>(config))
                a.copy(
                    adapters = a.adapters + ("amazon" to jsonObject {
                        "app_key" hasValue "3ccfbae1-8911-4c7d-8ad5-d8bdc4025ee8"
                        "slots" hasValue jsonArray {
                            putValues(listOf(
                                jsonObject {
                                    "slot_uuid" hasValue "0e421d37-a482-42d9-bbff-0234150ba92e"
                                    "format" hasValue "BANNER"
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "070d5ec1-61a6-4268-83da-9dd123738d97"
                                    "format" hasValue "MREC"
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "d9cad3e2-5cb8-4bb2-81a3-11140ea6dfd8"
                                    "format" hasValue "INTERSTITIAL"
                                },
//                                jsonObject {
//                                    "slot_uuid" hasValue "2c3bf7ca-aefe-477c-8cec-32b59a4449d7"
//                                    "format" hasValue "VIDEO"
//                                },
                            ))
                        }
                    })
                )
            }
        }
    }
}

private const val ConfigRequestPath = "config"
