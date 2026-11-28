package com.evolute.telemetrypro.data

/**
 * Builds trip records from already-collected local telemetry.
 * A trip starts only after at least 0.2 km of forward odometer movement.
 * It ends after the vehicle is not ready and has remained stationary for 5 minutes.
 */
class TripRecorder {
    fun record(db: SnapshotDb, telemetry: ApiClient.Telemetry, charging: Boolean, now: Long) {
        val history = db.recentSamples(12)
        val previous = history.lastOrNull()
        val active = db.activeTrip()
        val lat = telemetry.sensors["lat"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = telemetry.sensors["lon"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }

        if (active == null) {
            if (charging || history.size < 2) return
            val baseline = history.firstOrNull { now - it.capturedAtMillis in 0..600_000L } ?: return
            val distance = telemetry.odometer - baseline.odometerKm
            if (distance >= 0.2 && distance <= 5.0) {
                db.startTrip(baseline.capturedAtMillis, baseline.odometerKm, baseline.socPercent, null, null)
            }
            return
        }

        val lastMovement = when {
            previous == null -> active.lastMovementMs
            telemetry.odometer - previous.odometerKm in 0.05..5.0 -> now
            else -> active.lastMovementMs
        }

        if (previous != null && telemetry.odometer < previous.odometerKm - 0.1) {
            db.finishTrip(active.id, now, "interrupted")
            return
        }

        db.updateTrip(active.id, telemetry.odometer, telemetry.soc, lat, lon, lastMovement)

        if (!telemetry.ready && !charging && now - lastMovement >= STOP_TIMEOUT_MS) {
            db.finishTrip(active.id, now, "completed")
        }
    }

    companion object { private const val STOP_TIMEOUT_MS = 5 * 60 * 1000L }
}
