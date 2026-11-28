package com.evolute.telemetrypro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class SnapshotDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "evolute_telemetry_pro.sqlite", null, 5) {
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
        createChargingSessionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTripsTable(db)
        } else if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trips ADD COLUMN ready_off_since_ms INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN charging_unplugged_ms INTEGER")
            db.execSQL("ALTER TABLE trips ADD COLUMN gps_reference_lat REAL")
            db.execSQL("ALTER TABLE trips ADD COLUMN gps_reference_lon REAL")
        }
        if (oldVersion < 4) createChargingSessionsTable(db)
        if (oldVersion < 5) upgradeChargingSessions(db)
    }

    private fun createChargingSessionsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS charging_sessions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER,
            start_soc REAL,
            end_soc REAL,
            energy_started_at_ms INTEGER,
            energy_stopped_at_ms INTEGER,
            cable_removed_at_ms INTEGER,
            cable_inserted_soc REAL,
            soc_before_energy REAL,
            soc_after_energy REAL,
            battery_temp_before REAL,
            battery_temp_after REAL,
            has_energy_state_sensor INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_charging_started ON charging_sessions(started_at_ms DESC)")
        createChargingIntervalsTable(db)
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
        id: Long, odometer: Double, soc: Double, lat: Double?, lon: Double?, lastMovement: Long
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

    @Synchronized fun tripMetrics(trip: TripRecord): TripMetrics {
        val endMs = trip.endedAtMs ?: trip.lastMovementMs
        val temperatures = readableDatabase.rawQuery(
            "SELECT sensors_json FROM samples WHERE captured_ms BETWEEN ? AND ? ORDER BY captured_ms",
            arrayOf(trip.startedAtMs.toString(), endMs.toString())
        ).use { c ->
            val values = ArrayList<Double>()
            while (c.moveToNext()) {
                val sensors = org.json.JSONObject(c.getString(0))
                val value = listOf("batteryTemp", "batteryTemperature", "tractionBatteryTemp")
                    .firstNotNullOfOrNull { key -> sensors.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() } }
                if (value != null) values.add(value)
            }
            values
        }
        return TripMetrics.calculate(trip, temperatures)
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
        recordChargingSession(now, t, charging)
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


    private fun createChargingIntervalsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS charging_intervals(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            started_at_ms INTEGER NOT NULL,
            ended_at_ms INTEGER,
            FOREIGN KEY(session_id) REFERENCES charging_sessions(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_charging_intervals_session ON charging_intervals(session_id, started_at_ms)")
    }

    private fun upgradeChargingSessions(db: SQLiteDatabase) {
        val additions = listOf(
            "energy_started_at_ms INTEGER", "energy_stopped_at_ms INTEGER", "cable_removed_at_ms INTEGER",
            "cable_inserted_soc REAL", "soc_before_energy REAL", "soc_after_energy REAL",
            "battery_temp_before REAL", "battery_temp_after REAL",
            "has_energy_state_sensor INTEGER NOT NULL DEFAULT 0"
        )
        additions.forEach { definition ->
            val column = definition.substringBefore(' ')
            val exists = db.rawQuery("PRAGMA table_info(charging_sessions)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == column) found = true
                found
            }
            if (!exists) db.execSQL("ALTER TABLE charging_sessions ADD COLUMN $definition")
        }
        // Keep legacy timestamps intact while marking old sessions as lacking energy-state telemetry.
        db.execSQL("UPDATE charging_sessions SET cable_inserted_soc=start_soc WHERE cable_inserted_soc IS NULL")
        db.execSQL("UPDATE charging_sessions SET cable_removed_at_ms=ended_at_ms, soc_after_energy=end_soc WHERE cable_removed_at_ms IS NULL AND ended_at_ms IS NOT NULL")
        db.execSQL("UPDATE charging_sessions SET has_energy_state_sensor=0")
        createChargingIntervalsTable(db)
    }

    private fun sensorNumber(sensors: Map<String, String>, keys: List<String>): Double? =
        keys.firstNotNullOfOrNull { key -> sensors[key]?.toDoubleOrNull()?.takeIf { it.isFinite() } }

    private fun sensorBoolean(sensors: Map<String, String>, keys: List<String>): Boolean? =
        keys.firstNotNullOfOrNull { key ->
            sensors[key]?.trim()?.lowercase()?.let { value ->
                when (value) {
                    "true", "1", "yes", "on", "charging", "active" -> true
                    "false", "0", "no", "off", "idle", "not_charging", "not charging" -> false
                    else -> null
                }
            }
        }

    /** Null means no recognized, explicit energy-transfer state was present in this snapshot. */
    private fun energyTransferState(sensors: Map<String, String>): Boolean? {
        sensorBoolean(sensors, listOf("isCharging", "chargingActive", "isChargeActive",
            "isBatteryCharging", "chargeInProgress", "isChargingActive"))?.let { return it }
        val state = sensors.entries.firstOrNull {
            it.key.equals("chargingStatus", true) || it.key.equals("chargeStatus", true) ||
                it.key.equals("chargingState", true) || it.key.equals("chargeState", true)
        }?.value?.trim()?.lowercase()
        if (state != null) {
            if (state in setOf("charging", "active", "in_progress", "in progress", "fast_charging", "slow_charging")) return true
            if (state in setOf("idle", "stopped", "complete", "completed", "not_charging", "not charging", "disconnected")) return false
        }
        // Also recognize provider-specific names, while explicitly excluding the cable sensor.
        val dynamicState = sensors.entries.firstOrNull { (key, _) ->
            val normalized = key.lowercase()
            normalized != "ischargingguninserted" &&
                (normalized.contains("charging") || normalized.contains("charge")) &&
                (normalized.contains("state") || normalized.contains("status") || normalized.contains("active"))
        }?.let { (key, _) -> sensorBoolean(sensors, listOf(key)) }
        if (dynamicState != null) return dynamicState
        val powerKeys = sensors.keys.filter { key ->
            val normalized = key.lowercase()
            normalized != "ischargingguninserted" &&
                (normalized.contains("charge") || normalized.contains("charging")) &&
                (normalized.contains("power") || normalized.contains("current"))
        }
        val power = sensorNumber(sensors, listOf("chargingPower", "chargePower", "batteryChargingPower",
            "dcChargingPower", "acChargingPower", "chargerPower", "chargingCurrent", "chargeCurrent") + powerKeys)
        return power?.let { it > 0.0 }
    }

    private fun validSoc(value: Double): Double? = value.takeIf { it.isFinite() && it in 0.0..100.0 }

    @Synchronized private fun recordChargingSession(at: Long, telemetry: ApiClient.Telemetry, plugInserted: Boolean) {
        val db = writableDatabase
        val sensors = telemetry.sensors
        val energyState = energyTransferState(sensors)
        val temp = sensorNumber(sensors, listOf("batteryTemp", "batteryTemperature", "tractionBatteryTemp"))
        val soc = validSoc(telemetry.soc)
        var sessionId = db.rawQuery(
            "SELECT id FROM charging_sessions WHERE cable_removed_at_ms IS NULL ORDER BY id DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (plugInserted && sessionId == null) {
            val values = ContentValues().apply {
                put("started_at_ms", at)
                putNull("ended_at_ms")
                if (soc == null) putNull("start_soc") else put("start_soc", soc)
                putNull("end_soc")
                if (soc == null) putNull("cable_inserted_soc") else put("cable_inserted_soc", soc)
                put("has_energy_state_sensor", if (energyState != null) 1 else 0)
            }
            sessionId = db.insertOrThrow("charging_sessions", null, values)
        }
        if (sessionId == null) return

        if (energyState != null) {
            db.execSQL("UPDATE charging_sessions SET has_energy_state_sensor=1 WHERE id=?", arrayOf(sessionId))
        }
        val openInterval = db.rawQuery(
            "SELECT id FROM charging_intervals WHERE session_id=? AND ended_at_ms IS NULL ORDER BY id DESC LIMIT 1",
            arrayOf(sessionId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (plugInserted && energyState == true && openInterval == null) {
            // Capture the last persisted reading before this snapshot first reports active transfer.
            val previous = db.rawQuery(
                "SELECT soc,sensors_json FROM samples ORDER BY id DESC LIMIT 1", null
            ).use { c ->
                if (!c.moveToFirst()) null else {
                    val previousSensors = org.json.JSONObject(c.getString(1))
                    val previousTemp = listOf("batteryTemp", "batteryTemperature", "tractionBatteryTemp")
                        .firstNotNullOfOrNull { key -> previousSensors.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() } }
                    Pair(c.getDouble(0).takeIf { it.isFinite() && it in 0.0..100.0 }, previousTemp)
                }
            }
            val hasStarted = db.rawQuery(
                "SELECT energy_started_at_ms FROM charging_sessions WHERE id=?", arrayOf(sessionId.toString())
            ).use { c -> c.moveToFirst() && !c.isNull(0) }
            val values = ContentValues().apply {
                if (!hasStarted) {
                    put("energy_started_at_ms", at)
                    val beforeSoc = previous?.first ?: soc
                    val beforeTemp = previous?.second ?: temp
                    if (beforeSoc == null) putNull("soc_before_energy") else put("soc_before_energy", beforeSoc)
                    if (beforeTemp == null) putNull("battery_temp_before") else put("battery_temp_before", beforeTemp)
                }
                // A resumed transfer clears the previous pause boundary; it is still the same cable session.
                putNull("energy_stopped_at_ms")
                putNull("soc_after_energy")
                putNull("battery_temp_after")
            }
            db.update("charging_sessions", values, "id=?", arrayOf(sessionId.toString()))
            db.insertOrThrow("charging_intervals", null, ContentValues().apply {
                put("session_id", sessionId); put("started_at_ms", at); putNull("ended_at_ms")
            })
        } else if (energyState == false && openInterval != null) {
            db.update("charging_intervals", ContentValues().apply { put("ended_at_ms", at) },
                "id=?", arrayOf(openInterval.toString()))
            db.update("charging_sessions", ContentValues().apply {
                put("energy_stopped_at_ms", at)
                if (soc == null) putNull("soc_after_energy") else put("soc_after_energy", soc)
                if (temp == null) putNull("battery_temp_after") else put("battery_temp_after", temp)
            }, "id=?", arrayOf(sessionId.toString()))
        }

        if (!plugInserted) {
            if (openInterval != null) {
                db.update("charging_intervals", ContentValues().apply { put("ended_at_ms", at) },
                    "id=?", arrayOf(openInterval.toString()))
                db.update("charging_sessions", ContentValues().apply {
                    put("energy_stopped_at_ms", at)
                    if (soc == null) putNull("soc_after_energy") else put("soc_after_energy", soc)
                    if (temp == null) putNull("battery_temp_after") else put("battery_temp_after", temp)
                }, "id=?", arrayOf(sessionId.toString()))
            }
            val transferStarted = db.rawQuery(
                "SELECT energy_started_at_ms FROM charging_sessions WHERE id=?", arrayOf(sessionId.toString())
            ).use { c -> c.moveToFirst() && !c.isNull(0) }
            val insertedSoc = db.rawQuery(
                "SELECT cable_inserted_soc FROM charging_sessions WHERE id=?", arrayOf(sessionId.toString())
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }
            db.update("charging_sessions", ContentValues().apply {
                put("cable_removed_at_ms", at); put("ended_at_ms", at)
                if (soc == null) putNull("end_soc") else put("end_soc", soc)
                if (!transferStarted) {
                    if (insertedSoc == null) putNull("soc_after_energy") else put("soc_after_energy", insertedSoc)
                }
            }, "id=? AND cable_removed_at_ms IS NULL", arrayOf(sessionId.toString()))
        }
    }

    fun recentChargingSessions(limit: Int = 50): List<ChargingSession> =
        readableDatabase.rawQuery(
            """SELECT s.id,s.started_at_ms,s.energy_started_at_ms,s.energy_stopped_at_ms,
               s.cable_removed_at_ms,s.cable_inserted_soc,s.soc_before_energy,s.soc_after_energy,
               s.battery_temp_before,s.battery_temp_after,s.has_energy_state_sensor,
               COALESCE((SELECT SUM(MAX(0, COALESCE(i.ended_at_ms, s.energy_stopped_at_ms, CAST(strftime('%s','now') AS INTEGER)*1000) - i.started_at_ms))
                 FROM charging_intervals i WHERE i.session_id=s.id),0)
               FROM charging_sessions s ORDER BY s.started_at_ms DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            val result = ArrayList<ChargingSession>()
            while (c.moveToNext()) {
                result.add(ChargingSession(
                    id=c.getLong(0), cableInsertedAtMs=c.getLong(1),
                    energyStartedAtMs=if (c.isNull(2)) null else c.getLong(2),
                    energyStoppedAtMs=if (c.isNull(3)) null else c.getLong(3),
                    cableRemovedAtMs=if (c.isNull(4)) null else c.getLong(4),
                    cableInsertedSoc=if (c.isNull(5)) null else c.getDouble(5),
                    socBeforeEnergy=if (c.isNull(6)) null else c.getDouble(6),
                    socAfterEnergy=if (c.isNull(7)) null else c.getDouble(7),
                    batteryTempBefore=if (c.isNull(8)) null else c.getDouble(8),
                    batteryTempAfter=if (c.isNull(9)) null else c.getDouble(9),
                    hasEnergyStateSensor=c.getInt(10) == 1,
                    energyDurationMs=c.getLong(11)
                ))
            }
            result
        }

    @Synchronized fun recentSocSamples(sinceMs: Long, limit: Int = 1500): List<SocSample> {
        val db = readableDatabase
        val total = db.rawQuery(
            "SELECT COUNT(*) FROM samples WHERE captured_ms >= ?", arrayOf(sinceMs.toString())
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        if (total == 0L) return emptyList()
        // Keep the chart light even for a full month, while retaining the first and latest samples.
        val stride = kotlin.math.ceil(total.toDouble() / limit.coerceAtLeast(2)).toLong().coerceAtLeast(1L)
        return db.rawQuery(
            """SELECT s.captured_ms,s.soc,s.odometer,s.online,s.ready,s.charging,s.sensors_json,
                      (SELECT MAX(p.captured_ms) FROM samples p
                       WHERE p.captured_ms < s.captured_ms AND p.captured_ms >= ?) AS previous_sample_ms
               FROM samples s
               WHERE s.captured_ms >= ?
                 AND (s.id % ? = 0
                      OR s.id = (SELECT MIN(f.id) FROM samples f WHERE f.captured_ms >= ?)
                      OR s.id = (SELECT MAX(l.id) FROM samples l WHERE l.captured_ms >= ?))
               ORDER BY s.captured_ms ASC""",
            arrayOf(sinceMs.toString(), sinceMs.toString(), stride.toString(),
                sinceMs.toString(), sinceMs.toString())
        ).use { c ->
            val result = ArrayList<SocSample>()
            while (c.moveToNext()) {
                val sensors = org.json.JSONObject(c.getString(6))
                val temp = listOf("batteryTemp", "batteryTemperature", "tractionBatteryTemp")
                    .firstNotNullOfOrNull { key -> sensors.optString(key).toDoubleOrNull()?.takeIf { it.isFinite() } }
                val previousMs = if (c.isNull(7)) null else c.getLong(7)
                val hasGap = previousMs != null && c.getLong(0) - previousMs > 90_000L
                result.add(SocSample(c.getLong(0), c.getDouble(1), c.getDouble(2),
                    c.getInt(3) == 1, c.getInt(4) == 1, c.getInt(5) == 1, temp, hasGap))
            }
            result
        }
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