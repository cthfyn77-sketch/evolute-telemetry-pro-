package com.evolute.telemetrypro.data

/** A locally captured telemetry point used for conservative trip reconstruction. */
data class TripHistorySample(
    val capturedAtMs: Long,
    val odometerKm: Double,
    val socPercent: Double,
    val online: Boolean,
    val charging: Boolean = false
)

object TripHistoryAnalyzer {
    const val MAX_SAMPLE_GAP_MS = 90_000L
    const val MAX_STATIONARY_MS = 5 * 60_000L
    private const val MAX_PLAUSIBLE_INTERVAL_KM = 5.0

    data class Trip(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startOdometerKm: Double,
        val endOdometerKm: Double,
        val distanceKm: Double,
        val startSocPercent: Double,
        val endSocPercent: Double
    ) {
        val socUsedPercent: Double get() = (startSocPercent - endSocPercent).coerceAtLeast(0.0)
    }

    data class Report(
        val trips: List<Trip>,
        val excludedGaps: Int,
        val ignoredOdometerJumps: Int,
        val sampleCount: Int,
        val distanceTodayKm: Double,
        val distanceSinceLastChargeKm: Double?
    ) {
        val totalDistanceKm: Double get() = trips.sumOf { it.distanceKm }
        val totalSocUsedPercent: Double get() = trips.sumOf { it.socUsedPercent }
        val averageDistanceKm: Double get() =
            if (trips.isEmpty()) 0.0 else totalDistanceKm / trips.size
        val longestTripKm: Double get() = trips.maxOfOrNull { it.distanceKm } ?: 0.0

        companion object {
            fun empty() = Report(emptyList(), 0, 0, 0, 0.0, null)
        }
    }

    fun analyze(input: List<TripHistorySample>, nowMs: Long = System.currentTimeMillis(), zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): Report {
        val samples = input.asSequence()
            .filter { it.capturedAtMs > 0 && it.odometerKm.isFinite() && it.odometerKm >= 0.0 &&
                it.socPercent.isFinite() && it.socPercent in 0.0..100.0 }
            .sortedBy { it.capturedAtMs }
            .distinctBy { it.capturedAtMs }
            .toList()
        if (samples.size < 2) return Report(emptyList(), 0, 0, samples.size, 0.0, null)

        val trips = mutableListOf<Trip>()
        var start: TripHistorySample? = null
        var lastMovement: TripHistorySample? = null
        var distance = 0.0
        var excludedGaps = 0
        var ignoredJumps = 0

        fun finish() {
            val first = start
            val last = lastMovement
            if (first != null && last != null && distance > 0.0) {
                trips += Trip(first.capturedAtMs, last.capturedAtMs, first.odometerKm,
                    last.odometerKm, distance, first.socPercent, last.socPercent)
            }
            start = null
            lastMovement = null
            distance = 0.0
        }

        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            val elapsed = current.capturedAtMs - previous.capturedAtMs
            if (elapsed <= 0L) continue
            if (elapsed > MAX_SAMPLE_GAP_MS || !previous.online || !current.online || previous.charging || current.charging) {
                if (elapsed > MAX_SAMPLE_GAP_MS) excludedGaps++
                finish()
                continue
            }

            val delta = current.odometerKm - previous.odometerKm
            if (delta < 0.0) {
                finish()
                continue
            }
            if (delta > MAX_PLAUSIBLE_INTERVAL_KM) {
                ignoredJumps++
                finish()
                continue
            }
            if (delta > 0.0) {
                if (start == null) start = previous
                distance += delta
                lastMovement = current
            } else {
                val last = lastMovement
                if (last != null && current.capturedAtMs - last.capturedAtMs > MAX_STATIONARY_MS) {
                    finish()
                }
            }
        }
        finish()
        val orderedTrips = trips.asReversed()
        val dayStartMs = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zoneId)
            .atStartOfDay(zoneId).toInstant().toEpochMilli()
        val distanceToday = orderedTrips.filter { it.startedAtMs >= dayStartMs && it.startedAtMs <= nowMs }.sumOf { it.distanceKm }
        val lastChargeAtMs = samples.lastOrNull { it.charging }?.capturedAtMs
        val sinceLastCharge = lastChargeAtMs?.let { chargeAt ->
            orderedTrips.filter { it.startedAtMs >= chargeAt }.sumOf { it.distanceKm }
        }
        return Report(orderedTrips, excludedGaps, ignoredJumps, samples.size, distanceToday, sinceLastCharge)
    }
}
