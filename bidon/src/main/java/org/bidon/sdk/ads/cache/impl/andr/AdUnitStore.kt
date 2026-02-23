package org.bidon.sdk.ads.cache.impl.andr

import android.os.SystemClock
import kotlinx.coroutines.flow.update
import org.bidon.sdk.auction.models.AdUnit
import java.util.concurrent.TimeUnit

internal class AdUnitStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : AdStore<AdUnit, AdUnitStore.Entry>(Int.MAX_VALUE, AdStore.Entry.PriceComparator) {
    override fun insert(vararg items: AdUnit) {
        val now = SystemClock.elapsedRealtime()
        entries.update { old ->
            val updated =
                entrySet().apply {
                    addAll(items.map { Entry(it, now + ttlMs) }.filterNotExpired())
                    addAll(old.filterNotExpired())
                }
            while (updated.size > capacity) {
                updated.remove(updated.last())
            }
            updated
        }
    }

    override fun clear() {
        entries.update { entrySet() }
    }

    override fun Entry.unwrap(): AdUnit = result

    internal class Entry(
        val result: AdUnit,
        val expireAt: Long,
    ) : AdStore.Entry {
        override val demandId: String
            get() = result.demandId

        override val price: Double
            get() = result.pricefloor

        override val isExpired: Boolean
            get() = SystemClock.elapsedRealtime() > expireAt

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return demandId == other.demandId
        }

        override fun hashCode(): Int = demandId.hashCode()
    }

    companion object {
        private val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(29)
    }
}
