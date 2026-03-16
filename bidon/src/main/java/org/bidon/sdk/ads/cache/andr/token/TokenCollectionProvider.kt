package org.bidon.sdk.ads.cache.andr.token

import org.bidon.sdk.ads.AdType
import java.util.concurrent.ConcurrentHashMap

internal class TokenCollectionProvider {

    private val circuitBreakers = ConcurrentHashMap<AdType, TokenCircuitBreaker>()

    @Synchronized
    fun circuitBreaker(adType: AdType, tag: String): TokenCircuitBreaker =
        circuitBreakers.getOrPut(adType) {
            TokenCircuitBreaker(tag = tag)
        }
}
