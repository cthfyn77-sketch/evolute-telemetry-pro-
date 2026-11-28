package com.evolute.telemetrypro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class SnapshotDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "evolute_telemetry_pro.sqlite", null, 3) {
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
        createTripsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTripsTable(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trips ADD COLUMN ready_off_since_ms INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN charging_unplugged_ms INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN gps_reference_lat REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN gps_reference_lon REAL")
        }
    }

    private fun createTripsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS trips(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER,
            start_odometer REAL NOT NULL,
            end_odometer REAL NOT NULL,
            start_soc REAL NOT NULL,
            end_soc REAL NOT NULL,
            start_lat REAL,
            start_lon REAL,
            end_lat REAL,
            end_lon REAL,
            last_movement_ms INTEGER NOT NULL,
            status TEXT NOT NULL,
            ready_off_since_ms INTEGER,
            charging_unplugged_ms INTEGER,
            gps_reference_lat REAL,
            gps_reference_lon REAL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_started ON trips(started_at_ms DESC)")
    }

    private val tripColumns = "id,started_at_ms,ended_at_ms,start_odometer,end_odometer,start_soc,end_soc,start_lat,start_lon,end_lat,end_lon,last_movement_ms,status,ready_off_since_ms,charging_unplugged_ms,gps_reference_lat,gps_reference_lon"

    @Synchronized fun activeTrip(): TripRecord? = readableDatabase.rawQuery(
        "SELECT $tripColumns FROM trips WHERE ended_at_ms IS NULL ORDER BY id DESC LIMIT 1", null
    ).use { c -> if (c.moveToFirst()) tripFromCursor(c) else null }

    @Synchronized fun startTrip(at: Long, odometer: Double, soc: Double, lat: Double?, lon: Double?): Long {
        val values = ContentValues().apply {
            put("started_at_ms", at); putNull("ended_at_ms")
            put("start_odometer", odometer); put("end_odometer", odometer)
            put("start_soc", soc); put("end_soc", soc)
            if (lat == null) putNull("start_lat") else put("start_lat", lat)
            if (lon == null) putNull("start_lon") else put("start_lon", lon)
            putNull("end_lat"); putNull("end_lon")
            put("last_movement_ms", at); put("status", "active")
            putNull("ready_off_since_ms"); putNull("charging_unplugged_ms")
            if (lat == null) putNull("gps_reference_lat") else put("gps_reference_lat", lat)
            if (lon == null) putNull("gps_reference_lon") else put("gps_reference_lon", lon)
        }
        return writableDatabase.insertOrThrow("trips", null, values)
    }

    @Synchronized fun updateTrip(
        id: Long, odometer: Double, soc: Double, lat: Double?, lon: Double, lastMovement: Long
    ) {
        updateTripState(id, odometer, soc, lat, lon, lastMovement, "active", null, null, lat, lon)
    }

    @Synchronized fun updateTripState(
        id: Long,
        odometer: Double,
        soc: Double,
        lat: Double?,
        lon: Double?,
        lastMovement: Long,
        status: String,
        readyOffSinceMs: Long?,
        chargingUnpluggedMs: Long?,
        gpsReferenceLat: Double?,
        gpsReferenceLon: Double?
    ) {
        val values = ContentValues().apply {
            put("end_odometer", odometer); put("end_soc", soc); put("last_movement_ms", lastMovement)
            put("status", status)
            if (lat == null) putNull("end_lat") else put("end_lat", lat)
            if (lon == null) putNull("end_lon") else put("end_lon", lon)
            if (readyOffSinceMs == null) putNull("ready_off_since_ms") else put("ready_off_since_ms", readyOffSinceMs)
            if (chargingUnpluggedMs == null) putNull("charging_unplugged_ms") else put("charging_unplugged_ms", chargingUnpluggedMs)
            if (gpsReferenceLat == null) putNull("gps_reference_lat") else put("gps_reference_lat", gpsReferenceLat)
            if (gpsReferenceLon == null) putNull("gps_reference_lon") else put("gps_reference_lon", gpsReferenceLon)
        }
        writableDatabase.update("trips", values, "id=? AND ended_at_ms IS NULL", arrayOf(id.toString()))
    }

    @Synchronized fun finishTrip(id: Long, at: Long, status: String = "completed") {
        val values = ContentValues().apply { put("ended_at_ms", at); put("status", status) }
        writableDatabase.update("trips", values, "id=? AND ended_at_ms IS NULL", arrayOf(id.toString()))
    }

    fun recentTrips(limit: Int = 50): List<TripRecord> = readableDatabase.rawQuery(
        "SELECT $tripColumns FROM trips ORDER BY started_at_ms DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        val result = ArrayList<TripRecord>()
        while (c.moveToNext()) result.add(tripFromCursor(c))
        result
    }

    fun recentTripSamples(limit: Int = 25): List<TripSample> =
        readableDatabase.rawQuery(
            "SELECT captured_ms,soc,odometer,ready,charging,sensors_json FROM samples ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val result = ArrayList<TripSample>()
            while (c.moveToNext()) {
                val sensors = org.json.JSONObject(c.getString(5))
                val lat = sensors.optString("lat").toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
                val lon = sensors.optString("lon").toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
                result.add(TripSample(c.getLong(0), c.getDouble(1), c.getDouble(2),
                    c.getInt(3) == 1, c.getInt(4) == 1, lat, lon))
            }
            result.reverse()
            result
        }

    private fun tripFromCursor(c: android.database.Cursor) = TripRecord(
        id=c.getLong(0), startedAtMs=c.getLong(1), endedAtMs=if (c.isNull(2)) null else c.getLong(2),
        startOdometer=c.getDouble(3), endOdometer=c.getDouble(4), startSoc=c.getDouble(5), endSoc=c.getDouble(6),
        startLat=if (c.isNull(7)) null else c.getDouble(7), startLon=if (c.isNull(8)) null else c.getDouble(8),
        endLat=if (c.isNull(9)) null else c.getDouble(9), endLon=if (c.isNull(10)) null else c.getDouble(10),
        lastMovementMs=c.getLong(11), status=c.getString(12),
        readyOffSinceMs=if (c.isNull(13)) null else c.getLong(13),
        chargingUnpluggedMs=if (c.isNull(14)) null else c.getLong(14),
        gpsReferenceLat=if (c.isNull(15)) null else c.getDouble(15),
        gpsReferenceLon=if (c.isNull(16)) null else c.getDouble(16)
    )

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
            "SELECT odometer,soc,captured_ms,charging FROM samples ORDER BY id DESC LIMIT ?",
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