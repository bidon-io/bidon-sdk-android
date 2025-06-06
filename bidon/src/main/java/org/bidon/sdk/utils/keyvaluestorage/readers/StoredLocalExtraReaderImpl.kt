package org.bidon.sdk.utils.keyvaluestorage.readers

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/**
 * Concrete implementation of [StoredLocalExtraReader] that retrieves
 * the raw string value stored under "stored_local_extras" from Android's
 * default (public) SharedPreferences.
 *
 * This is used to pass structured impression metadata or auction data
 * between SDK modules, mediation layers, or ad sources.
 *
 * The result is wrapped as a Map with a single key-value pair,
 * making it composable with other parameter sets for request payloads.
 */
internal class StoredLocalExtraReaderImpl(
    context: Context
) : StoredLocalExtraReader {

    companion object {
        /**
         * Constant key used to access shared impression data.
         */
        private const val STORED_LOCAL_EXTRAS_KEY = "stored_local_extras"
    }

    /**
     * Lazy-initialized default SharedPreferences.
     * Uses application context to ensure proper lifecycle.
     */
    private val sharedPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    /**
     * Reads the raw JSON string under "stored_local_extras" and returns it as a
     * single-entry map: { "stored_local_extras" to rawValue }.
     *
     * @return Map with one entry or an empty map if key is not set or value is null.
     * Safe to call from any thread. Internally dispatched to IO.
     */
    override suspend fun readAsMap(): Map<String, String> {
        val value = sharedPreferences.getString(STORED_LOCAL_EXTRAS_KEY, null)
        return if (value != null) {
            mapOf(STORED_LOCAL_EXTRAS_KEY to value)
        } else {
            emptyMap()
        }
    }
}
