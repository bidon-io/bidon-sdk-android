package org.bidon.sdk.utils.keyvaluestorage.readers

/**
 * Reads the raw string value of the "stored_local_extras" key
 * from default (public) SharedPreferences.
 *
 * Intended for cross-module or SDK communication where impression data,
 * ad metadata, or structured payloads are passed via a shared storage layer.
 */
internal interface StoredLocalExtraReader {
    /**
     * @return The raw JSON string stored under "stored_local_extras",
     * or null if the key is missing or unset.
     */
    fun read(): String?
}
