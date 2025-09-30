package org.bidon.sdk.utils.json

import org.json.JSONObject

/**
 * Created by Bidon Team on 08/02/2023.
 */
internal interface JsonParser<T> {
    fun parseOrNull(jsonString: String): T?
}

public interface JsonSerializer<T> {
    public fun serialize(data: T): JSONObject
}