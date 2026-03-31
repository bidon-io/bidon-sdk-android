package org.bidon.sdk.ads.cache.andr.token

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class TokenCircuitBreaker(
    private val tag: String,
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 30_000L,
) {
    private class State(
        val consecutiveFailures: Int,
        val openedAt: Long
    )

    private val states = MutableStateFlow<Map<String, State>>(emptyMap())

    fun isOpen(demandId: String): Boolean {
        val state = states.value[demandId] ?: return false
        if (state.consecutiveFailures < failureThreshold) {
            return false
        }

        if (SystemTimeNow - state.openedAt < cooldownMs) {
            return true
        }

        states.update { it - demandId }.also {
            logInfo(tag, "Half-open: $demandId (cooldown elapsed)")
        }

        return false
    }

    fun record(
        demandId: String,
        tokenStatus: String
    ) {
        states.update {
            if (tokenStatus == TokenInfo.Status.SUCCESS.code) {
                it - demandId
            } else {
                val current = it[demandId]
                val failures = (current?.consecutiveFailures ?: 0) + 1
                val justOpened =
                    failures >= failureThreshold &&
                        (current == null || current.consecutiveFailures < failureThreshold)
                it + (demandId to
                    State(
                        failures,
                        if (justOpened) SystemTimeNow else current?.openedAt ?: 0L
                    )
                    )
            }
        }
    }
}
