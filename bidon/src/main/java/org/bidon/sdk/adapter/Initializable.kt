package org.bidon.sdk.adapter

import android.app.Activity

/**
 * Created by Aleksei Cherniaev on 07/09/2023.
 *
 * Shows if an adapter should be initialized with additional parameters.
 */
interface Initializable<T : AdapterParameters> {
    suspend fun init(activity: Activity, configParams: T)
    fun parseConfigParam(json: String): T
}