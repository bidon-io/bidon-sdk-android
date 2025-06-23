package org.bidon.sdk.databinders.user.impl

import android.adservices.appsetid.AppSetId
import android.adservices.appsetid.AppSetIdManager
import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.databinders.user.AppSetIdRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AppSetIdRepositoryImpl(
    private val context: Context
) : AppSetIdRepository {

    private val cachedAppSetId = AtomicReference<AppSetId?>(null)
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mutex = Mutex()

    override suspend fun getAppSetId(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(SdkExtensions.AD_SERVICES) >= 7
        ) {
            getOrFetchAppSetId(context)?.id
        } else {
            null
        }
    }

    override suspend fun isDeveloperScope(): Boolean? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SdkExtensions.getExtensionVersion(SdkExtensions.AD_SERVICES) >= 7
        ) {
            getOrFetchAppSetId(context)?.scope?.let { it == AppSetId.SCOPE_DEVELOPER }
        } else {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresExtension(extension = SdkExtensions.AD_SERVICES, version = 7)
    private suspend fun getOrFetchAppSetId(context: Context): AppSetId? {
        cachedAppSetId.get()?.let { return it }
        return runCatching {
            mutex.withLock {
                fetchAppSetId(context).also { cachedAppSetId.set(it) }
            }
        }.onFailure { exception ->
            Log.e(TAG, "error during receiving AppSetId", exception)
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresExtension(extension = SdkExtensions.AD_SERVICES, version = 7)
    private suspend fun fetchAppSetId(context: Context): AppSetId =
        suspendCancellableCoroutine { continuation ->
            try {
                AppSetIdManager.get(context).getAppSetId(
                    executor,
                    object : OutcomeReceiver<AppSetId, Exception> {
                        override fun onResult(appSetId: AppSetId?) {
                            if (continuation.isActive) {
                                if (appSetId != null) {
                                    continuation.resume(appSetId)
                                } else {
                                    continuation.resumeWithException(
                                        IllegalStateException("Failed to get AppSetId")
                                    )
                                }
                            }
                        }

                        override fun onError(error: Exception) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
}

private const val TAG = "AppSetIdManager"
