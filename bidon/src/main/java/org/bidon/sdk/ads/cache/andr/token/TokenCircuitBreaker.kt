package org.bidon.sdk.ads.cache.andr.token

import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.concurrent.ConcurrentHashMap

internal class TokenCircuitBreaker(
    private val tag: String,
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 30_000L,
) {
    private class State(val consecutiveFailures: Int, val openedAt: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun isOpen(demandId: String): Boolean {
        val state = states[demandId] ?: return false
        if (state.consecutiveFailures < failureThreshold) return false
        if (SystemTimeNow - state.openedAt >= cooldownMs) {
            states.remove(demandId)
            logInfo(tag, "Half-open: $demandId (cooldown elapsed)")
            return false
        }
        return true
    }

    fun record(demandId: String, tokenStatus: String) {
        if (tokenStatus == TokenInfo.Status.SUCCESS.code) {
            states.remove(demandId)
        } else {
            states.compute(demandId) { _, current ->
                val failures = (current?.consecutiveFailures ?: 0) + 1
                val justOpened = failures >= failureThreshold
                    && (current == null || current.consecutiveFailures < failureThreshold)
                if (justOpened) logInfo(tag, "OPEN: $demandId after $failures failures")
                State(
                    consecutiveFailures = failures,
                    openedAt = if (justOpened) SystemTimeNow else current?.openedAt ?: 0L,
                )
            }
        }
    }
}
