package org.bidon.sdk.utils.keyvaluestorage.readers

/**
 * Provides access to the raw JSON string stored under the key "stored_local_extras"
 * in Android's default (public) SharedPreferences.
 *
 * This interface is used to extract structured auxiliary data—such as impression metadata—
 * that may be injected into ad requests, analytics payloads, or bidding signals.
 *
 * The returned result is formatted as a [Map] to enable seamless merging with other
 * parameter sets across SDK modules.
 */
internal interface StoredLocalExtraReader {

    /**
     * Reads the value of the "stored_local_extras" key from default SharedPreferences.
     *
     * @return A single-entry [Map] with the key "stored_local_extras" mapped to its
     *         raw JSON string value, or an empty map if no value is present.
     *
     * @throws Exception never — safe fallback to emptyMap on error or null value.
     */
    suspend fun readAsMap(): Map<String, String>
}
