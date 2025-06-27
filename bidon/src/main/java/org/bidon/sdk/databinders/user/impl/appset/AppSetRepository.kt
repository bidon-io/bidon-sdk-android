package org.bidon.sdk.databinders.user.impl.appset

import android.content.Context

internal interface AppSetRepository {
    suspend fun getAppSetId(context: Context): String?
    suspend fun getAppSetIdScope(context: Context): String?
}
