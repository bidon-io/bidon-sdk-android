package org.bidon.sdk.utils.keyvaluestorage.readers

/**
 * Provides read access to the raw JSON payload stored under the "stored_local_extras" key
 * in Android's default (public) SharedPreferences.
 *
 * The result is returned as a single-entry [Map], making it easily composable with other
 * request-level parameters or analytics payloads.
 */
internal interface StoredLocalExtraReader {

    /**
     * Retrieves the value associated with the "stored_local_extras" key and returns it
     * as a [Map] in the form: `"stored_local_extras" to rawJsonString`.
     *
     * @return A [Map] with one entry if the key is present and holds a non-null value,
     * or an empty map otherwise. This method never throws and is safe for composition.
     */
    suspend fun readAsMap(): Map<String, String>
}
