package org.bidon.sdk.databinders.user

internal interface AppSetIdRepository {
    suspend fun getAppSetId(): String?
    suspend fun isDeveloperScope(): Boolean?
}