package org.bidon.sdk.databinders.user.impl.appset

import android.content.Context
import android.util.Log
import com.google.android.gms.appset.AppSet
import com.google.android.gms.appset.AppSetIdInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object AppSetIdInfoRepository : AppSetRepository {
    private val cachedAppSetIdInfo = AtomicReference<AppSetIdInfo?>(null)
    private val mutex = Mutex()

    override suspend fun getAppSetId(context: Context) =
        getOrFetchAppSetIdInfo(context)?.id

    override suspend fun getAppSetIdScope(context: Context) =
        when (getOrFetchAppSetIdInfo(context)?.scope) {
            AppSetIdInfo.SCOPE_DEVELOPER -> DEVELOPER_SCOPE
            AppSetIdInfo.SCOPE_APP -> APP_SCOPE
            else -> null
        }

    private suspend fun getOrFetchAppSetIdInfo(context: Context): AppSetIdInfo? {
        cachedAppSetIdInfo.get()?.let { return it }
        return runCatching {
            mutex.withLock {
                fetchAppSetIdInfo(context).also { cachedAppSetIdInfo.set(it) }
            }
        }.onFailure { exception ->
            Log.e(TAG, "error during receiving AppSetId", exception)
        }.getOrNull()
    }

    private suspend fun fetchAppSetIdInfo(context: Context): AppSetIdInfo? =
        suspendCancellableCoroutine { continuation ->
            try {
                AppSet.getClient(context).appSetIdInfo
                    .addOnSuccessListener { info ->
                        cachedAppSetIdInfo.set(info)
                        continuation.resume(info)
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
}

private const val TAG = "AppSetIdInfoManager"
private const val DEVELOPER_SCOPE = "developer"
private const val APP_SCOPE = "app"
