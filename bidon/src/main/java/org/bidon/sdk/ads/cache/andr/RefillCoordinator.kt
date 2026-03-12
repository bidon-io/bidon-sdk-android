package org.bidon.sdk.ads.cache.andr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.AuctionResultStore
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.logs.logging.impl.logInfo

internal class RefillCoordinator(
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val store: AdStore<AuctionResultStore.Entry>,
    private val strategy: AdCacheStrategy,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    @Volatile
    var lastAdTypeParam: AdTypeParam? = null

    @Volatile
    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    suspend fun join() { job?.join() }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun maybeStart(
        isLoading: Boolean,
        block: suspend CoroutineScope.(AdTypeParam) -> Unit,
    ) {
        val adTypeParam = lastAdTypeParam ?: return

        val refillThreshold = strategy.refillThreshold
        if (store.peekAll().size > refillThreshold) return
        if (isLoading || isActive) return

        logInfo(tag, "Background refill triggered (threshold=$refillThreshold)")
        job = scope.launch { block(adTypeParam) }
    }
}
