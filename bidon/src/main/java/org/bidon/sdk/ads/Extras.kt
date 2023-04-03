package org.bidon.sdk.ads

/**
 * Created by Aleksei Cherniaev on 24/03/2023.
 *
 * Allows to collect extra data.
 */
interface Extras {
    /**
     * @param key name of extra data
     * @param value value of extra data. Null removes data if exists.
     *              Possible types are String, Int, Long, Double, Float, Boolean, Char
     */
    fun addExtra(key: String, value: Any?)
    fun getExtras(): Map<String, Any>
}

internal class ExtrasImpl : Extras {

    private val extras = mutableMapOf<String, Any>()

    override fun addExtra(key: String, value: Any?) {
        if (value != null && value.isTypeSupported()) {
            extras[key] = value
        } else {
            extras.remove(key)
        }
    }

    override fun getExtras(): Map<String, Any> = extras.toMap()

    private fun Any.isTypeSupported(): Boolean {
        return this is String ||
            this is Int ||
            this is Long ||
            this is Double ||
            this is Float ||
            this is Boolean ||
            this is Char
    }
}