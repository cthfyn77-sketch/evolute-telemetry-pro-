package com.evolute.telemetrypro.data

/**
 * Estimates net SOC consumption over observed, contiguous driving intervals.
 * Charging samples, offline periods, telemetry gaps and implausible jumps are excluded.
 * SOC percentage points are not converted to kWh.
 */
object DrivingEfficiencyAnalyzer {
    const val MAX_SAMPLE_GAP_MS = 90_000L
    private const val MAX_INTERVAL_DISTANCE_KM = 5.0
    private const val MAX_ABS_SOC_CHANGE_PERCENT = 5.0

    data class Report(
        val distanceKm: Double,
        val socConsumedPercent: Double,
        val intervalCount: Int,
        val excludedIntervalCount: Int
    ) {
        val socPer100Km: Double?
            get() = if (distanceKm > 0.0 && socConsumedPercent > 0.0)
                socConsumedPercent / distanceKm * 100.0 else null
        val kmPerSocPercent: Double?
            get() = if (socConsumedPercent > 0.0)
                distanceKm / socConsumedPercent else null

        companion object {
            fun empty() = Report(0.0, 0.0, 0, 0)
        }
    }

    fun analyze(input: List<TripHistorySample>): Report {
        val samples = input.asSequence()
            .filter { it.capturedAtMs > 0L && it.odometerKm.isFinite() && it.odometerKm >= 0.0 &&
                it.socPercent.isFinite() && it.socPercent in 0.0..100.0 }
            .sortedBy { it.capturedAtMs }
            .distinctBy { it.capturedAtMs }
            .toList()

        var distance = 0.0
        var socUsed = 0.0
        var valid = 0
        var excluded = 0

        for ((previous, current) in samples.zipWithNext()) {
            val elapsed = current.capturedAtMs - previous.capturedAtMs
            val deltaKm = current.odometerKm - previous.odometerKm
            val deltaSoc = previous.socPercent - current.socPercent
            val acceptable = elapsed in 1..MAX_SAMPLE_GAP_MS &&
                previous.online && current.online &&
                !previous.charging && !current.charging &&
                deltaKm > 0.0 && deltaKm <= MAX_INTERVAL_DISTANCE_KM &&
                deltaSoc.isFinite() && kotlin.math.abs(deltaSoc) <= MAX_ABS_SOC_CHANGE_PERCENT

            if (acceptable) {
                distance += deltaKm
                socUsed += deltaSoc
                valid++
            } else {
                excluded++
            }
        }

        return Report(distance, socUsed, valid, excluded)
    }
}
