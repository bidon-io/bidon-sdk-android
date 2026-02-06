package org.bidon.sdk.ads.cache.impl.alex

import android.content.Context
import android.content.SharedPreferences
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.utils.di.get

/**
 * Collects user impression signals and persists them using SharedPreferences.
 * Data is stored per ad type (Banner, Interstitial, Rewarded).
 */
internal class UserFlow {
    private val context: Context by lazy { get() }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Keys per AdType
    private fun keyPreviousPrice(adType: AdType) = "${adType.code}_previous_price"
    private fun keyImpressionsCount(adType: AdType) = "${adType.code}_impressions_count"
    private fun keyTotalPrice(adType: AdType) = "${adType.code}_total_price"

    // ===== Getters =====

    /**
     * Returns the price of the most recent impression for the given ad type.
     * Returns 0.0 if no impressions have been recorded.
     */
    fun getPreviousImpressionPrice(adType: AdType): Double {
        return prefs.getFloat(keyPreviousPrice(adType), 0f).toDouble()
    }

    /**
     * Returns the total number of impressions recorded for the given ad type.
     */
    fun getImpressionsCount(adType: AdType): Int {
        return prefs.getInt(keyImpressionsCount(adType), 0)
    }

    /**
     * Returns the average price across all impressions for the given ad type.
     * Returns 0.0 if no impressions have been recorded.
     */
    fun getAveragePrice(adType: AdType): Double {
        val count = getImpressionsCount(adType)
        if (count == 0) return 0.0
        val total = prefs.getFloat(keyTotalPrice(adType), 0f)
        return total.toDouble() / count
    }

    // ===== Record Impression =====

    /**
     * Records an impression with the given price for the specified ad type.
     * Updates previous price, increments count, and updates total for average calculation.
     */
    fun recordImpression(adType: AdType, price: Double) {
        val currentCount = getImpressionsCount(adType)
        val currentTotal = prefs.getFloat(keyTotalPrice(adType), 0f)

        prefs.edit()
            .putFloat(keyPreviousPrice(adType), price.toFloat())
            .putInt(keyImpressionsCount(adType), currentCount + 1)
            .putFloat(keyTotalPrice(adType), currentTotal + price.toFloat())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "userflow"
    }
}
