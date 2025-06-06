package org.bidon.sdk.utils.keyvaluestorage.readers

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/**
 * Implementation of [StoredLocalExtraReader] that accesses the value stored under
 * the "stored_local_extras" key in Android's default SharedPreferences
 * (via [PreferenceManager.getDefaultSharedPreferences]).
 *
 * The returned value is wrapped in a singleton [Map] to support consistent merging
 * with other parameter sets when forming ad requests or auction signals.
 */
internal class StoredLocalExtraReaderImpl(
    context: Context
) : StoredLocalExtraReader {

    companion object {
        /**
         * SharedPreferences key that stores the serialized extras payload.
         */
        private const val STORED_LOCAL_EXTRAS_KEY = "stored_local_extras"
    }

    /**
     * Lazily initialized SharedPreferences instance backed by the application context.
     * Safe to use across lifecycle boundaries and multiple threads.
     */
    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    /**
     * Reads the raw string value associated with "stored_local_extras" and returns it
     * as a single-entry [Map] where the key is the same as the SharedPreferences key.
     *
     * @return A [Map] containing one entry if the value is present and non-null,
     * or an empty map otherwise.
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
