package org.bidon.sdk.config.impl

import android.content.Context
import kotlinx.coroutines.*
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterParameters
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.Initializable
import org.bidon.sdk.adapter.SupportsTestMode
import org.bidon.sdk.config.models.ConfigResponse
import org.bidon.sdk.config.usecases.InitAndRegisterAdaptersUseCase
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import kotlin.system.measureTimeMillis

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
@Suppress("UNCHECKED_CAST")
internal class InitAndRegisterAdaptersUseCaseImpl(
    private val adaptersSource: AdaptersSource
) : InitAndRegisterAdaptersUseCase {

    private val scope get() = CoroutineScope(SdkDispatchers.Single)

    override suspend operator fun invoke(
        context: Context,
        adapters: List<Adapter>,
        configResponse: ConfigResponse,
        isTestMode: Boolean
    ) = coroutineScope {
        runCatching {
            val adapterList = adapters.toMutableSet()
            logInfo(TAG, "Adapters: ${adapterList.joinToString { it.demandId.demandId }}")
//            withTimeoutOrNull(configResponse.initializationTimeout) {
            val groupedAdapters = configResponse.adapters.toList()
                .groupBy { (_, initJson) -> initJson.optInt("order", 0) }
                .toList()
                .sortedBy { (order, _) -> order }
                .onEach { (order, adaptersInfo) ->
                    logInfo(TAG, "Initialization order #$order: ${adaptersInfo.joinToString { it.first }}")
                }
            groupedAdapters.forEach { (order, adaptersInfo) ->
                logInfo(TAG, "Start initialization #$order: ${adaptersInfo.joinToString { it.first }}")
                val nextAdaptersGroup = adaptersInfo
                    .mapNotNull { (demandId, _) ->
                        adapterList.find { it.demandId.demandId == demandId }
                    }.also {
                        adapterList.removeAll(it.toSet())
                    }
                val deferredList = nextAdaptersGroup.map { adapter ->
                    val demandId = adapter.demandId
                    scope.async {
                        runCatching {
                            // set test mode param
                            (adapter as? SupportsTestMode)?.isTestMode = isTestMode

                            // initialize if needed
                            val initializable = adapter as? Initializable<AdapterParameters>
                            if (initializable == null) {
                                adapter
                            } else {
                                val timeStart = measureTimeMillis {
                                    val adapterParameters =
                                        parseAdapterParameters(configResponse, adapter).getOrThrow()
                                    adapter.init(context, adapterParameters)
                                }
                                logInfo(TAG, "Adapter ${demandId.demandId} initialized in $timeStart ms.")
                            }
                        }.onSuccess {
                            /**
                             * Add adapter to [AdaptersSource] only if it was initialized successfully.
                             */
                            /**
                             * Add adapter to [AdaptersSource] only if it was initialized successfully.
                             */
                            adaptersSource.add(adapter)
                        }.onFailure { cause ->
                            logError(TAG, "Adapter not initialized: ${demandId.demandId}: ${cause.message}", cause)
                        }.getOrNull()
                    }
                }
                withTimeoutOrNull(configResponse.initializationTimeout) {
                    deferredList.forEach { it.await() }
                }
//                }
            }
        }
        logInfo(TAG, "Registered adapters: ${adaptersSource.adapters.joinToString { it::class.java.simpleName }}")
    }

    private fun parseAdapterParameters(
        configResponse: ConfigResponse,
        adapter: Initializable<AdapterParameters>
    ): Result<AdapterParameters> = runCatching {
        val json = configResponse.adapters[(adapter as Adapter).demandId.demandId]
        requireNotNull(json) {
            "No config found for Adapter($adapter). Adapter not initialized."
        }
        adapter.parseConfigParam(json.toString())
    }
}

private const val TAG = "InitAndRegisterUserCase"
