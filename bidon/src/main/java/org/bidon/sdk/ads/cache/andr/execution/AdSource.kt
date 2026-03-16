package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.logs.logging.impl.logError

internal fun AdSource<*>.destroySafe(tag: String) {
    try {
        destroy()
    } catch (e: Exception) {
        logError(tag, "destroy() failed: demandId=$demandId", e)
    }
}

internal val AdSource<*>.price: Double
    get() = getStats().price
