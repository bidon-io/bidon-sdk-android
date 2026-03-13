package org.bidon.sdk.ads.cache.andr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal class RefillCoordinator(
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val store: AdStore<AuctionResultStore.Entry>,
    private val strategy: AdCacheStrategy,
) {
    private data class BackoffState(
        val consecutiveFailures: Int = 0,
        val lastAuctionTimeoutMs: Long = 0,
        val lastFailureTimestamp: Long = 0,
    )

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val lastAdTypeParamRef = AtomicReference<AdTypeParam?>(null)

    private val jobRef = AtomicReference<Job?>(null)

    private val backoffState = MutableStateFlow(BackoffState())

    private val users: MutableSet<Any> = Collections.newSetFromMap(ConcurrentHashMap())

    val isActive: Boolean get() = jobRef.get()?.isActive == true

    @Synchronized
    fun acquire(
        owner: Any,
        adTypeParam: AdTypeParam
    ) {
        users.add(owner)

        lastAdTypeParamRef.set(adTypeParam)

        logInfo(tag, "acquired, users=${users.size}")
    }

    @Synchronized
    fun release(owner: Any) {
        if (!users.remove(owner) || users.isNotEmpty()) return

        jobRef.getAndSet(null)?.cancel()

        backoffState.update { BackoffState() }

        logInfo(tag, "Refill canceled, failures reset")
    }

    suspend fun join() {
        jobRef.get()?.join()
    }

    fun recordResult(
        filled: Boolean,
        auctionTimeoutMs: Long
    ) {
        backoffState.update {
            if (filled) {
                it.copy(lastAuctionTimeoutMs = auctionTimeoutMs, consecutiveFailures = 0)
            } else {
                it.copy(
                    lastAuctionTimeoutMs = auctionTimeoutMs,
                    consecutiveFailures = it.consecutiveFailures + 1,
                    lastFailureTimestamp = SystemTimeNow
                )
            }
        }

        logInfo(
            tag,
            "Refill result: filled=$filled, timeout=${auctionTimeoutMs}ms, failures=${backoffState.value.consecutiveFailures}"
        )
    }

    fun recordFailure() {
        backoffState.update {
            it.copy(
                consecutiveFailures = it.consecutiveFailures + 1,
                lastFailureTimestamp = SystemTimeNow
            )
        }

        logInfo(tag, "Refill failure recorded, failures=${backoffState.value.consecutiveFailures}")
    }

    @Synchronized
    fun maybeStart(
        isLoading: Boolean,
        block: suspend CoroutineScope.(AdTypeParam) -> Unit,
    ) {
        if (isLoading) {
            logInfo(tag, "Refill skipped, isLoading=true")
            return
        }

        if (isActive) {
            logInfo(tag, "Refill skipped, isActive=true")
            return
        }

        val adTypeParam =
            lastAdTypeParamRef.get() ?: run {
                logInfo(tag, "Refill skipped, no adTypeParam")
                return
            }

        val refillThreshold = strategy.refillThreshold
        val storeSize = store.size
        if (storeSize > refillThreshold) {
            logInfo(tag, "Refill skipped, store=$storeSize above threshold=$refillThreshold")
            return
        }

        val (failures, timeoutMs, lastFailure) = backoffState.value
        if (failures > 1 && timeoutMs > 0) {
            val backoffMs = calculateBackoffMs(failures, timeoutMs)
            val elapsed = SystemTimeNow - lastFailure
            if (elapsed < backoffMs) {
                logInfo(tag, "Backoff active: ${elapsed}ms < ${backoffMs}ms (failures=$failures)")
                return
            }

            logInfo(tag, "Backoff expired: ${elapsed}ms >= ${backoffMs}ms (failures=$failures)")
        }

        logInfo(tag, "Background refill triggered (threshold=$refillThreshold, failures=$failures)")

        jobRef.set(scope.launch { block(adTypeParam) })
    }

    private fun calculateBackoffMs(
        failures: Int,
        auctionTimeoutMs: Long
    ): Long = auctionTimeoutMs * min(MAX_MULTIPLIER, failures - 1)

    companion object {
        private const val MAX_MULTIPLIER = 2
    }
}
