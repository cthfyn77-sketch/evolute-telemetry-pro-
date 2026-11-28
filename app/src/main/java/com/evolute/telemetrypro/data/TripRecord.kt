package com.evolute.telemetrypro.data

data class TripRecord(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val startOdometer: Double,
    val endOdometer: Double,
    val startSoc: Double,
    val endSoc: Double,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val lastMovementMs: Long,
    val status: String,
    val readyOffSinceMs: Long? = null,
    val chargingUnpluggedMs: Long? = null,
    val gpsReferenceLat: Double? = null,
    val gpsReferenceLon: Double? = null
) {
    val distanceKm: Double get() = (endOdometer - startOdometer).coerceAtLeast(0.0)
    val socUsed: Double get() = startSoc - endSoc
    val durationMs: Long get() = ((endedAtMs ?: lastMovementMs) - startedAtMs).coerceAtLeast(0L)
}

data class TripSample(
    val capturedAtMs: Long,
    val soc: Double,
    val odometerKm: Double,
    val ready: Boolean,
    val charging: Boolean,
    val lat: Double?,
    val lon: Double?
)