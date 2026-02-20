package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType

/**
 * Encapsulates RTB token storage with TTL-based expiration.
 * Each network's token expires independently after [RTB_TOKEN_EXPIRATION_MS].
 *
 * Backed by the same mutable map reference from [CachePersistedState],
 * so tokens persist across instance recreations.
 */
internal class RtbTokenStore(
    private val storedTokens: MutableMap<String, StoredToken>,
) {
    /**
     * Stored RTB token with expiration tracking.
     */
    data class StoredToken(
        val tokenInfo: TokenInfo,
        val storedAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now - storedAt > RTB_TOKEN_EXPIRATION_MS
    }

    /**
     * Returns valid (non-expired) RTB tokens, removing expired ones.
     * Each network's expiration is tracked independently.
     */
    fun getValidTokens(): Map<String, TokenInfo> {
        val now = System.currentTimeMillis()
        val sizeBefore = storedTokens.size
        storedTokens.entries.removeAll { (_, stored) -> stored.isExpired(now) }
        val removedCount = sizeBefore - storedTokens.size
        if (removedCount > 0) {
            logInfo(TAG, "getValidTokens(): removed $removedCount expired tokens")
        }
        val valid = storedTokens.mapValues { it.value.tokenInfo }
        logInfo(TAG, "getValidTokens(): ${valid.size} valid tokens: [${valid.keys.joinToString()}]")
        return valid
    }

    /**
     * Stores RTB tokens from the round with current timestamp.
     * Tokens expire after [RTB_TOKEN_EXPIRATION_MS].
     * Skips demand IDs that returned no-bid in this round.
     */
    fun storeFromRound(
        adUnits: List<AdUnit>,
        noBidDemandIds: Set<String>,
        roundTokens: Map<String, TokenInfo>,
    ) {
        val rtbUnits = adUnits
            .filter { it.bidType == BidType.RTB }
            .filter { it.demandId !in noBidDemandIds }
        val now = System.currentTimeMillis()
        var storedCount = 0
        for (unit in rtbUnits) {
            val token = roundTokens[unit.demandId]
            if (token != null) {
                storedTokens[unit.demandId] = StoredToken(token, now)
                storedCount++
                logInfo(TAG, "storeFromRound(): stored ${unit.demandId} token (expires in ${RTB_TOKEN_EXPIRATION_MS / 60_000}min)")
            }
        }
        logInfo(TAG, "storeFromRound(): stored $storedCount tokens, total=${storedTokens.size}: [${storedTokens.keys.joinToString()}]")
    }

    /**
     * Removes the stored token for a network whose ad was shown.
     * The token was consumed to load the shown ad and cannot produce a valid bid again.
     */
    fun removeToken(demandId: String) {
        val removed = storedTokens.remove(demandId)
        if (removed != null) {
            logInfo(TAG, "removeToken(): removed token for $demandId (ad shown)")
        } else {
            logInfo(TAG, "removeToken(): no stored token for $demandId")
        }
    }

    /**
     * Refreshes expired RTB tokens for the given demand IDs by calling the [fetcher].
     * Returns the merged map of valid + freshly fetched tokens.
     */
    suspend fun refreshExpired(
        rtbDemandIds: Set<String>,
        fetcher: suspend () -> Map<String, TokenInfo>,
    ): Map<String, TokenInfo> {
        val validTokens = getValidTokens()
        val expiredRtbDemandIds = rtbDemandIds - validTokens.keys

        return if (expiredRtbDemandIds.isNotEmpty()) {
            logInfo(TAG, "refreshExpired(): refreshing tokens for: $expiredRtbDemandIds")
            val freshTokens = fetcher()
                .filterKeys { it in expiredRtbDemandIds }
            val now = System.currentTimeMillis()
            freshTokens.forEach { (demandId, token) ->
                storedTokens[demandId] = StoredToken(token, now)
            }
            logInfo(TAG, "refreshExpired(): refreshed ${freshTokens.size} tokens")
            validTokens + freshTokens
        } else {
            validTokens
        }
    }
}

private const val TAG = "AdCacheVladimir.RtbTokenStore"
private const val RTB_TOKEN_EXPIRATION_MS = 15 * 60 * 1000L // 15 minutes
