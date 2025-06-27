package org.bidon.sdk.databinders.user.impl

import android.content.Context
import org.bidon.sdk.databinders.user.impl.appset.AppSetRepositoryProvider
import org.bidon.sdk.databinders.user.impl.appset.AppSetRepositoryProviderImpl

internal class AppSetIdReceiver(
    private val context: Context
) {

    internal val repositoryProvider: AppSetRepositoryProvider = AppSetRepositoryProviderImpl()

    suspend fun getAppSetId() =
        repositoryProvider.provide().getAppSetId(context)

    suspend fun getAppSetIdScope() =
        repositoryProvider.provide().getAppSetIdScope(context)
}
