package com.evolute.telemetrypro.data

data class TripRoutePoint(
    val capturedAtMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val speedMetersPerSecond: Double?
)
