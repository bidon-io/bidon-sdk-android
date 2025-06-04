package org.bidon.sdk.utils.keyvaluestorage.readers

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

/**
 * Concrete implementation of [StoredLocalExtraReader] that retrieves
 * the "stored_local_extras" value from Android's default SharedPreferences
 * (i.e. PreferenceManager.getDefaultSharedPreferences).
 *
 * This storage is commonly used for exchanging structured data between libraries,
 * such as impression IDs or session-based metadata.
 */
internal class StoredLocalExtraReaderImpl(
    context: Context
) : StoredLocalExtraReader {

    companion object {
        private const val STORED_LOCAL_EXTRAS_KEY = "stored_local_extras"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    }

    override fun read(): String? {
        return sharedPreferences.getString(STORED_LOCAL_EXTRAS_KEY, null)
    }
}
