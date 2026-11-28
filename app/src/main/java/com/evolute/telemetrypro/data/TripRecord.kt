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

data class TripMetrics(
    val averageSpeedKmh: Double?,
    val energyUsedKwh: Double?,
    val consumptionKwhPer100Km: Double?,
    val minBatteryTempC: Double?,
    val averageBatteryTempC: Double?,
    val maxBatteryTempC: Double?,
    val temperatureSamples: Int
) {
    companion object {
        const val BATTERY_CAPACITY_KWH = 52.0

        fun calculate(trip: TripRecord, temperatures: List<Double>): TripMetrics {
            val endMs = trip.endedAtMs ?: trip.lastMovementMs
            val durationMs = (endMs - trip.startedAtMs).coerceAtLeast(0L)
            val averageSpeed = if (durationMs > 0L && trip.distanceKm > 0.0) {
                trip.distanceKm / (durationMs / 3_600_000.0)
            } else null
            val socDrop = trip.startSoc - trip.endSoc
            val energy = if (socDrop >= 0.0 && socDrop <= 100.0) {
                socDrop / 100.0 * BATTERY_CAPACITY_KWH
            } else null
            val consumption = if (energy != null && trip.distanceKm > 0.0) {
                energy / trip.distanceKm * 100.0
            } else null
            val validTemps = temperatures.filter { it.isFinite() }
            return TripMetrics(
                averageSpeedKmh = averageSpeed,
                energyUsedKwh = energy,
                consumptionKwhPer100Km = consumption,
                minBatteryTempC = validTemps.minOrNull(),
                averageBatteryTempC = validTemps.takeIf { it.isNotEmpty() }?.average(),
                maxBatteryTempC = validTemps.maxOrNull(),
                temperatureSamples = validTemps.size
            )
        }
    }
}
