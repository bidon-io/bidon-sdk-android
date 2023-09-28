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
                        "app_key" hasValue "a9_onboarding_app_id"
                        "slots" hasValue jsonArray {
                            putValues(listOf(
                                jsonObject {
                                    "slot_uuid" hasValue "5ab6a4ae-4aa5-43f4-9da4-e30755f2b295"
                                    "format" hasValue "BANNER"
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "54fb2d08-c222-40b1-8bbe-4879322dc04b"
                                    "format" hasValue "MREC" // mrec
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "bed17ec3-b185-453e-b2a8-4a3c6bb9234d"
                                    "format" hasValue "LEADER_BOARD" // leaderboard
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "4e918ac0-5c68-4fe1-8d26-4e76e8f74831"
                                    "format" hasValue "INTERSTITIAL"
                                },
                                jsonObject {
                                    "slot_uuid" hasValue "4acc26e6-3ada-4ee8-bae0-753c1e0ad278"
                                    "format" hasValue "INTERSTITIAL"
                                },
                            ))
                        }
                    })
                )
            }
        }
    }
}

private const val ConfigRequestPath = "config"
