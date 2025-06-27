package org.bidon.sdk.databinders.user.impl.appset

internal class AppSetRepositoryProviderImpl : AppSetRepositoryProvider {

    override fun provide(): AppSetRepository {
            return AppSetIdInfoRepository
    }
}

internal interface AppSetRepositoryProvider {
    fun provide(): AppSetRepository
}
