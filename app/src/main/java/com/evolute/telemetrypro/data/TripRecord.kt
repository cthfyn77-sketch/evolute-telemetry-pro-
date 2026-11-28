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
    val status: String
) {
    val distanceKm: Double get() = (endOdometer - startOdometer).coerceAtLeast(0.0)
    val socUsed: Double get() = startSoc - endSoc
    val durationMs: Long get() = ((endedAtMs ?: lastMovementMs) - startedAtMs).coerceAtLeast(0L)
}
