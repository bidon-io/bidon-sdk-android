package org.bidon.sdk.ads.cache.denis.stores

import android.os.SystemClock

/**
 * TTL configuration and monotonic time utilities for cache expiration.
 *
 * Uses SystemClock.elapsedRealtime() for monotonic time source (immune to system time changes).
 */
internal object TtlConfig {
    /**
     * Time-to-live for cache entries in milliseconds (30 minutes).
     */
    const val TTL_MILLIS = 30 * 60 * 1000L // 30 minutes

    /**
     * Interval between periodic sweep operations in milliseconds (5 minutes).
     */
    const val SWEEP_INTERVAL_MILLIS = 5 * 60 * 1000L // 5 minutes

    /**
     * Returns current monotonic time in milliseconds since boot (including deep sleep).
     *
     * @return Current time from SystemClock.elapsedRealtime()
     */
    fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * Calculates expiration timestamp for a new cache entry.
     *
     * @return Timestamp when entry should expire (now + TTL_MILLIS)
     */
    fun expiresAt(): Long = now() + TTL_MILLIS

    /**
     * Checks if a cache entry has expired.
     *
     * @param expiresAt Expiration timestamp from cache entry
     * @return true if current time is past expiration timestamp
     */
    fun isExpired(expiresAt: Long): Boolean = now() > expiresAt
}
