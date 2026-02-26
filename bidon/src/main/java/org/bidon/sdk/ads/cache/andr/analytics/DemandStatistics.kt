package org.bidon.sdk.ads.cache.andr.analytics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.analytics.DemandMeasurement
import java.util.concurrent.TimeUnit

/**
 * SQLite repository for persisting and querying demand network statistics.
 */
internal class DemandStatistics(
    context: Context,
) {
    private val dbHelper = DatabaseHelper(context.applicationContext)

    /**
     * Records a batch of measurements to the database.
     */
    fun record(measurements: List<DemandMeasurement>) {
        if (measurements.isEmpty()) {
            return
        }

        dbHelper.writableDatabase.transaction {
            try {
                measurements.forEach {
                    val values =
                        ContentValues().apply {
                            put(COL_DEMAND_ID, it.demandId)
                            put(COL_AD_TYPE, it.adType.code)
                            put(COL_TIMESTAMP, it.timestamp)
                            put(COL_BID_PRICE, it.bidPrice)
                            put(COL_FILLED, if (it.filled) 1 else 0)
                            put(COL_LATENCY_MS, it.latencyMs)
                        }
                    insert(TABLE_MEASUREMENTS, null, values)
                }
            } finally {
            }
        }
    }

    /**
     * Returns aggregated statistics for all demand networks for the given ad type.
     */
    fun getAllStats(adType: AdType): List<Entry> {
        val stats = mutableListOf<Entry>()

        val query =
            """
            SELECT
                $COL_DEMAND_ID,
                COUNT(*) as sample_count,
                SUM($COL_FILLED) as fill_count,
                AVG($COL_FILLED) as fill_rate,
                AVG($COL_BID_PRICE) as avg_bid,
                AVG($COL_LATENCY_MS) as avg_latency,
                MIN($COL_BID_PRICE) as min_bid,
                MAX($COL_BID_PRICE) as max_bid
            FROM $TABLE_MEASUREMENTS
            WHERE $COL_AD_TYPE = ?
            GROUP BY $COL_DEMAND_ID
            """.trimIndent()

        dbHelper.readableDatabase
            .rawQuery(query, arrayOf(adType.code))
            .use {
                while (it.moveToNext()) {
                    stats.add(
                        Entry(
                            demandId = it.getString(0),
                            sampleCount = it.getInt(1),
                            fillCount = it.getInt(2),
                            fillRate = it.getDouble(3),
                            avgBidPrice = if (it.isNull(4)) null else it.getDouble(4),
                            avgLatencyMs = it.getDouble(5),
                            minBidPrice = if (it.isNull(6)) null else it.getDouble(6),
                            maxBidPrice = if (it.isNull(7)) null else it.getDouble(7)
                        )
                    )
                }
            }

        return stats
    }

    /**
     * Returns total sample count for the given ad type.
     */
    fun getSampleCount(adType: AdType): Int {
        val query = "SELECT COUNT(*) FROM $TABLE_MEASUREMENTS WHERE $COL_AD_TYPE = ?"
        return dbHelper.readableDatabase
            .rawQuery(query, arrayOf(adType.code))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Returns fill rate within a time window for the given ad type.
     *
     * @param adType Type of ad to query
     * @param windowMinutes Time window in minutes from now
     * @return Fill rate as a value between 0.0 and 1.0, or null if no data
     */
    fun getRecentFillRate(
        adType: AdType,
        windowMinutes: Long
    ): Double? {
        val cutoffTime =
            System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(windowMinutes)

        val query =
            """
            SELECT AVG($COL_FILLED)
            FROM $TABLE_MEASUREMENTS
            WHERE $COL_AD_TYPE = ? AND $COL_TIMESTAMP >= ?
            """.trimIndent()

        return dbHelper.readableDatabase
            .rawQuery(query, arrayOf(adType.code, cutoffTime.toString()))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else null }
    }

    /**
     * Returns eCPM distribution statistics within a time window.
     *
     * @param adType Type of ad to query
     * @param windowDays Time window in days from now
     * @return Map of demandId to list of bid prices, or empty map if no data
     */
    fun getBidDistribution(
        adType: AdType,
        windowDays: Long
    ): Map<String, List<Double>> {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(windowDays)
        val distribution = mutableMapOf<String, MutableList<Double>>()

        val query =
            """
            SELECT $COL_DEMAND_ID, $COL_BID_PRICE
            FROM $TABLE_MEASUREMENTS
            WHERE $COL_AD_TYPE = ?
                AND $COL_TIMESTAMP >= ?
                AND $COL_BID_PRICE IS NOT NULL
            ORDER BY $COL_DEMAND_ID, $COL_BID_PRICE
            """.trimIndent()

        dbHelper.readableDatabase
            .rawQuery(query, arrayOf(adType.code, cutoffTime.toString()))
            .use {
                while (it.moveToNext()) {
                    val demandId = it.getString(0)
                    val bidPrice = it.getDouble(1)
                    distribution.getOrPut(demandId) { mutableListOf() }.add(bidPrice)
                }
            }
        return distribution
    }

    fun savePriceFloor(
        adType: AdType,
        priceFloor: Double
    ) {
        val values =
            ContentValues().apply {
                put(COL_PF_AD_TYPE, adType.code)
                put(COL_PF_PRICE_FLOOR, priceFloor)
                put(COL_PF_UPDATED_AT, System.currentTimeMillis())
            }
        dbHelper.writableDatabase.insertWithOnConflict(
            TABLE_PRICE_FLOORS, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getPriceFloor(adType: AdType): Double {
        val query = "SELECT $COL_PF_PRICE_FLOOR FROM $TABLE_PRICE_FLOORS WHERE $COL_PF_AD_TYPE = ?"
        return dbHelper.readableDatabase
            .rawQuery(query, arrayOf(adType.code))
            .use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private class DatabaseHelper(
        context: Context
    ) : SQLiteOpenHelper(
            context,
            DATABASE_NAME,
            null,
            DATABASE_VERSION
        ) {
        override fun onCreate(db: SQLiteDatabase) {
            with(db) {
                execSQL(
                    """
                    CREATE TABLE $TABLE_MEASUREMENTS (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_DEMAND_ID TEXT NOT NULL,
                        $COL_AD_TYPE TEXT NOT NULL,
                        $COL_TIMESTAMP INTEGER NOT NULL,
                        $COL_BID_PRICE REAL,
                        $COL_FILLED INTEGER NOT NULL,
                        $COL_LATENCY_MS INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                execSQL("CREATE INDEX idx_ad_type ON $TABLE_MEASUREMENTS ($COL_AD_TYPE)")
                execSQL("CREATE INDEX idx_demand_ad_type ON $TABLE_MEASUREMENTS ($COL_DEMAND_ID, $COL_AD_TYPE)")
                execSQL("CREATE INDEX idx_timestamp ON $TABLE_MEASUREMENTS ($COL_TIMESTAMP)")
                execSQL(
                    """
                    CREATE TABLE $TABLE_PRICE_FLOORS (
                        $COL_PF_AD_TYPE TEXT PRIMARY KEY,
                        $COL_PF_PRICE_FLOOR REAL NOT NULL,
                        $COL_PF_UPDATED_AT INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            if (oldVersion < 2) {
                db.execSQL(
                    """
                    CREATE TABLE $TABLE_PRICE_FLOORS (
                        $COL_PF_AD_TYPE TEXT PRIMARY KEY,
                        $COL_PF_PRICE_FLOOR REAL NOT NULL,
                        $COL_PF_UPDATED_AT INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "bidon_demand_stats.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_MEASUREMENTS = "measurements"
        private const val COL_ID = "id"
        private const val COL_DEMAND_ID = "demand_id"
        private const val COL_AD_TYPE = "ad_type"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_BID_PRICE = "bid_price"
        private const val COL_FILLED = "filled"
        private const val COL_LATENCY_MS = "latency_ms"

        private const val TABLE_PRICE_FLOORS = "price_floors"
        private const val COL_PF_AD_TYPE = "ad_type"
        private const val COL_PF_PRICE_FLOOR = "price_floor"
        private const val COL_PF_UPDATED_AT = "updated_at"
    }

    /**
     * Aggregated statistics for a demand network.
     *
     * @property demandId Unique identifier for the demand network
     * @property sampleCount Total number of recorded measurements
     * @property fillRate Percentage of requests that were filled (0.0 to 1.0)
     * @property avgBidPrice Average eCPM bid price in USD (null if no bids)
     * @property avgLatencyMs Average response latency in milliseconds
     * @property minBidPrice Minimum recorded bid price in USD (null if no bids)
     * @property maxBidPrice Maximum recorded bid price in USD (null if no bids)
     */
    data class Entry(
        val demandId: String,
        val sampleCount: Int,
        val fillCount: Int,
        val fillRate: Double,
        val avgBidPrice: Double?,
        val avgLatencyMs: Double,
        val minBidPrice: Double?,
        val maxBidPrice: Double?
    )
}