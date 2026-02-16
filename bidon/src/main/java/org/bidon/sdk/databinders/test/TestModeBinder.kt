package org.bidon.sdk.databinders.test

import org.bidon.sdk.BidonSdk
import org.bidon.sdk.databinders.DataBinder

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal class TestModeBinder : DataBinder<Boolean> {
    override val fieldName: String = "test"

    override suspend fun getJsonObject(): Boolean = BidonSdk.bidon.isTestMode
}