package com.evolute.telemetrypro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class SnapshotDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "evolute_telemetry_pro.sqlite", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE samples(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            captured_at TEXT NOT NULL,
            captured_ms INTEGER NOT NULL,
            api_updated_at TEXT,
            soc REAL NOT NULL,
            odometer REAL NOT NULL,
            stock_range REAL,
            online INTEGER NOT NULL,
            ready INTEGER NOT NULL,
            charging INTEGER NOT NULL,
            sensors_json TEXT NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_samples_time ON samples(captured_ms)")
        db.execSQL("CREATE INDEX idx_samples_odo ON samples(odometer)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun insert(t: ApiClient.Telemetry, charging: Boolean = false) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("captured_at", Instant.ofEpochMilli(now).toString())
            put("captured_ms", now)
            put("api_updated_at", t.updatedAt)
            put("soc", t.soc); put("odometer", t.odometer); put("stock_range", t.stockRange)
            put("online", if (t.online) 1 else 0); put("ready", if (t.ready) 1 else 0)
            put("charging", if (charging) 1 else 0)
            put("sensors_json", org.json.JSONObject(t.sensors).toString())
        }
        writableDatabase.insertOrThrow("samples", null, values)
    }

    /** Chronological battery history for the selected period; raw sensor JSON is read unchanged. */
    fun batteryHistory(capturedAfterMs: Long? = null): List<BatteryHistorySample> {
        val sql = if (capturedAfterMs == null) {
            "SELECT captured_at,soc,odometer,sensors_json FROM samples ORDER BY captured_ms ASC"
        } else {
            "SELECT captured_at,soc,odometer,sensors_json FROM samples WHERE captured_ms >= ? ORDER BY captured_ms ASC"
        }
        val args = capturedAfterMs?.let { arrayOf(it.toString()) }
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    BatteryHistorySample(
                        capturedAt = cursor.getString(0),
                        soc = cursor.getDouble(1),
                        odometer = cursor.getDouble(2),
                        sensorsJson = cursor.getString(3)
                    )
                )
            }
        }
    }

    /** Wall-clock capture time of the newest locally persisted telemetry sample. */
    fun latestCapturedAtMs(): Long? = readableDatabase.rawQuery(
        "SELECT captured_ms FROM samples ORDER BY id DESC LIMIT 1", null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun latest(): ApiClient.Telemetry? = readableDatabase.rawQuery(
        "SELECT soc,odometer,stock_range,online,ready,api_updated_at,sensors_json FROM samples ORDER BY id DESC LIMIT 1", null
    ).use { c ->
        if (!c.moveToFirst()) return null
        val sensorsObj = org.json.JSONObject(c.getString(6))
        val sensors = sensorsObj.keys().asSequence().associateWith { sensorsObj.optString(it) }
        ApiClient.Telemetry(c.getDouble(0), c.getDouble(1), if (c.isNull(2)) null else c.getDouble(2),
            c.getInt(3) == 1, c.getInt(4) == 1, if (c.isNull(5)) null else c.getString(5), sensors)
    }

    fun recentSamples(limit: Int = 500): List<RangeEstimator.Sample> =
        readableDatabase.rawQuery(
            "SELECT odometer,soc,captured_ms,charging FROM samples WHERE online = 1 ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val result = ArrayList<RangeEstimator.Sample>()
            while (c.moveToNext()) result.add(RangeEstimator.Sample(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getInt(3) == 1))
            result.reverse()
            result
        }

    companion object {
        @Volatile private var instance: SnapshotDb? = null
        fun get(context: Context): SnapshotDb = instance ?: synchronized(this) {
            instance ?: SnapshotDb(context).also { instance = it }
        }
    }
}
