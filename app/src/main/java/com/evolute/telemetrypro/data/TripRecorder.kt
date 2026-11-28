package com.evolute.telemetrypro.data

/**
 * Reconstructs trips from 30-second telemetry snapshots.
 * READY is the driving-state signal; the charge-port sensor is treated as the
 * start of a charging session, including delayed sessions.
 */
class TripRecorder {
    fun record(db: SnapshotDb, telemetry: ApiClient.Telemetry, charging: Boolean, now: Long) {
        val history = db.recentTripSamples(25)
        val previous = history.lastOrNull()
        val lat = coordinate(telemetry.sensors["lat"], -90.0, 90.0)
        val lon = coordinate(telemetry.sensors["lon"], -180.0, 180.0)
        val current = TripSample(now, telemetry.soc, telemetry.odometer, telemetry.ready, charging, lat, lon)
        val active = db.activeTrip()

        if (active == null) {
            if (!telemetry.ready || charging || previous == null) return
            val gpsBaseline = history.lastOrNull {
                it.ready && !it.charging && now - it.capturedAtMs in 0..START_LOOKBACK_MS &&
                    it.lat != null && it.lon != null && lat != null && lon != null &&
                    distanceMeters(it.lat, it.lon, lat, lon) < GPS_MOVEMENT_METERS
            }
            val odometerBaseline = previous.takeIf {
                it.ready && !it.charging && now - it.capturedAtMs in 0..START_LOOKBACK_MS
            }
            val odometerMoved = odometerBaseline != null &&
                telemetry.odometer - odometerBaseline.odometerKm >= ODOMETER_EPSILON_KM
            val gpsMoved = gpsBaseline != null &&
                confirmedGpsDisplacement(history, current, gpsBaseline.lat, gpsBaseline.lon)
            val start = if (odometerMoved) odometerBaseline else if (gpsMoved) gpsBaseline else null
            if (start != null) {
                db.startTrip(start.capturedAtMs, start.odometerKm, start.soc, start.lat, start.lon)
            }
            return
        }

        val previousOdometer = previous?.odometerKm ?: active.endOdometer
        val odometerDelta = telemetry.odometer - previousOdometer
        val odometerMoved = odometerDelta >= ODOMETER_EPSILON_KM
        var gpsReferenceLat = active.gpsReferenceLat ?: active.endLat
        var gpsReferenceLon = active.gpsReferenceLon ?: active.endLon
        if (gpsReferenceLat == null || gpsReferenceLon == null) {
            gpsReferenceLat = lat
            gpsReferenceLon = lon
        }
        val gpsMoved = confirmedGpsDisplacement(history, current, gpsReferenceLat, gpsReferenceLon)
        val moved = telemetry.ready && !charging && (odometerMoved || gpsMoved)
        val lastMovement = if (moved) now else active.lastMovementMs
        if (gpsMoved && lat != null && lon != null) {
            gpsReferenceLat = lat
            gpsReferenceLon = lon
        }

        // Plug insertion takes precedence over READY-off timeout, even when
        // the charging session itself is scheduled to start later.
        if (charging) {
            db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                lastMovement, "charging", active.readyOffSinceMs, null, gpsReferenceLat, gpsReferenceLon)
            return
        }

        // A plugged-in session has ended. Give the same trip 15 minutes to resume.
        if (active.status == "charging") {
            db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                lastMovement, "waiting_after_charge", null, now, gpsReferenceLat, gpsReferenceLon)
            return
        }

        val unpluggedAt = active.chargingUnpluggedMs
        if (unpluggedAt != null) {
            if (now - unpluggedAt >= AFTER_CHARGE_RESUME_MS) {
                db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                    active.lastMovementMs, active.status, null, unpluggedAt, gpsReferenceLat, gpsReferenceLon)
                db.finishTrip(active.id, unpluggedAt + AFTER_CHARGE_RESUME_MS)
                return
            }
            if (moved) {
                db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                    now, "active", null, null, gpsReferenceLat, gpsReferenceLon)
            } else {
                db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                    active.lastMovementMs, "waiting_after_charge", null, unpluggedAt,
                    gpsReferenceLat, gpsReferenceLon)
            }
            return
        }

        val readyOffAt = active.readyOffSinceMs
        if (!telemetry.ready) {
            val offSince = readyOffAt ?: now
            if (now - offSince >= READY_OFF_CLOSE_MS) {
                db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                    active.lastMovementMs, "waiting_ready_off", offSince, null,
                    gpsReferenceLat, gpsReferenceLon)
                db.finishTrip(active.id, offSince + READY_OFF_CLOSE_MS)
            } else {
                db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                    active.lastMovementMs, "waiting_ready_off", offSince, null,
                    gpsReferenceLat, gpsReferenceLon)
            }
            return
        }

        // READY returned before the 10-minute deadline: keep the same trip,
        // but only treat it as resumed once movement is confirmed.
        if (readyOffAt != null && now - readyOffAt >= READY_OFF_CLOSE_MS) {
            db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
                active.lastMovementMs, "waiting_ready_off", readyOffAt, null,
                gpsReferenceLat, gpsReferenceLon)
            db.finishTrip(active.id, readyOffAt + READY_OFF_CLOSE_MS)
            return
        }
        val nextStatus = if (moved || readyOffAt == null) "active" else "waiting_ready_off"
        db.updateTripState(active.id, telemetry.odometer, telemetry.soc, lat, lon,
            lastMovement, nextStatus, if (moved) null else readyOffAt, null,
            gpsReferenceLat, gpsReferenceLon)
    }

    private fun confirmedGpsDisplacement(
        history: List<TripSample>,
        current: TripSample,
        anchorLat: Double?,
        anchorLon: Double?
    ): Boolean {
        // GPS is only evidence of driving when both consecutive snapshots were
        // captured with READY ON and the charge port unplugged. A single jump,
        // stale coordinate, or movement recorded while parked/charging is ignored.
        if (!current.ready || current.charging ||
            anchorLat == null || anchorLon == null || current.lat == null || current.lon == null
        ) return false

        val previous = history.lastOrNull() ?: return false
        if (!previous.ready || previous.charging ||
            previous.lat == null || previous.lon == null ||
            current.capturedAtMs - previous.capturedAtMs !in 0..MAX_GPS_SAMPLE_GAP_MS
        ) return false

        val previousDisplacement = distanceMeters(anchorLat, anchorLon, previous.lat, previous.lon)
        val currentDisplacement = distanceMeters(anchorLat, anchorLon, current.lat, current.lon)
        return previousDisplacement >= GPS_MOVEMENT_METERS &&
            currentDisplacement >= GPS_MOVEMENT_METERS
    }

    private fun coordinate(raw: String?, min: Double, max: Double): Double? =
        raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return earthRadius * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    companion object {
        private const val START_LOOKBACK_MS = 10 * 60 * 1000L
        private const val MAX_GPS_SAMPLE_GAP_MS = 2 * 60 * 1000L
        private const val GPS_MOVEMENT_METERS = 500.0
        private const val ODOMETER_EPSILON_KM = 0.001
        private const val READY_OFF_CLOSE_MS = 10 * 60 * 1000L
        private const val AFTER_CHARGE_RESUME_MS = 15 * 60 * 1000L
    }
}