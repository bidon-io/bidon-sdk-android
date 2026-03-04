package org.bidon.sdk.ads.cache.andr.store

import kotlinx.coroutines.flow.update
import org.bidon.sdk.ads.cache.andr.DEFAULT_TTL_MS
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class RtbResultStore(
    private val tag: String,
    val ttlMs: Long = DEFAULT_TTL_MS,
) : AdStore<RtbResultStore.Entry>(Int.MAX_VALUE, AdStore.Entry.PriceComparator) {
    override fun <T> insert(
        items: Collection<T>,
        transform: (T) -> Entry
    ) {
        entries.update {
            val updated =
                entrySet().apply {
                    addAll(items.map(transform).filterNotExpired())
                    addAll(it.filterNotExpired())
                }
            while (updated.size > capacity) {
                updated.remove(updated.last())
            }
            updated
        }
        logInfo(tag, "RtbResultStore.insert: +${items.size}, total=${entries.value.size}")
    }

    override fun clear() {
        val count = entries.value.size
        entries.update { entrySet() }
        logInfo(tag, "RtbResultStore.clear: removed $count entries")
    }

    internal class Entry(
        override val auctionId: String,
        override val tokenInfo: TokenInfo,
        val adUnit: AdUnit,
        val expireAt: Long = SystemTimeNow + DEFAULT_TTL_MS,
    ) : AdStore.Entry {
        override val demandId: String
            get() = adUnit.demandId

        override val price: Double
            get() = adUnit.pricefloor

        override val isExpired: Boolean
            get() = SystemTimeNow > expireAt

        fun unwrap(): Pair<AdUnit, TokenInfo> = adUnit to tokenInfo

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Entry

            if (tokenInfo.token != other.tokenInfo.token) return false
            if (demandId != other.demandId) return false

            return true
        }

        override fun hashCode(): Int {
            var result = tokenInfo.token?.hashCode() ?: 0
            result = 31 * result + demandId.hashCode()
            return result
        }
    }
}

internal fun Collection<RtbResultStore.Entry>.unwrap(): Map<AdUnit, TokenInfo> = associate(RtbResultStore.Entry::unwrap)

internal fun Collection<RtbResultStore.Entry>.asString(): String =
    buildString {
        append("(${this@asString.size}) ")
        append(joinToString { "${it.demandId}:${it.price}" })
    }
