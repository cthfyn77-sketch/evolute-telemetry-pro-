package com.evolute.telemetrypro.data

/** A locally captured telemetry point used for conservative trip reconstruction. */
data class TripHistorySample(
    val capturedAtMs: Long,
    val odometerKm: Double,
    val socPercent: Double,
    val online: Boolean,
    val charging: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null
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
        val endSocPercent: Double,
        val timeZoneId: String = "Europe/Moscow"
    ) {
        val socUsedPercent: Double get() = (startSocPercent - endSocPercent).coerceAtLeast(0.0)
        /** Observed timestamp span; may include short stationary pauses. */
        val observedDurationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
    }

    data class DailySummary(
        val date: java.time.LocalDate,
        val tripCount: Int,
        val totalDistanceKm: Double,
        val totalObservedDurationMs: Long,
        val totalSocUsedPercent: Double,
        val timeZoneId: String = "Europe/Moscow"
    )

    data class Report(
        val trips: List<Trip>,
        val excludedGaps: Int,
        val ignoredOdometerJumps: Int,
        val sampleCount: Int,
        val distanceTodayKm: Double,
        val distanceSinceLastChargeKm: Double?,
        val dailySummaries: List<DailySummary> = emptyList()
    ) {
        val totalDistanceKm: Double get() = trips.sumOf { it.distanceKm }
        val totalSocUsedPercent: Double get() = trips.sumOf { it.socUsedPercent }
        val averageDistanceKm: Double get() =
            if (trips.isEmpty()) 0.0 else totalDistanceKm / trips.size
        val longestTripKm: Double get() = trips.maxOfOrNull { it.distanceKm } ?: 0.0
        val totalObservedDurationMs: Long get() = trips.sumOf { it.observedDurationMs }
        val averageObservedDurationMs: Long get() =
            if (trips.isEmpty()) 0L else totalObservedDurationMs / trips.size
        val longestObservedDurationMs: Long get() = trips.maxOfOrNull { it.observedDurationMs } ?: 0L

        companion object {
            fun empty() = Report(emptyList(), 0, 0, 0, 0.0, null)
        }
    }

    fun analyze(
        input: List<TripHistorySample>,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: java.time.ZoneId = java.time.ZoneId.of("Europe/Moscow"),
        zoneForCoordinates: (latitude: Double, longitude: Double) -> java.time.ZoneId? = { _, _ -> null }
    ): Report {
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
        var activeZone: java.time.ZoneId? = null
        var excludedGaps = 0
        var ignoredJumps = 0

        fun finish() {
            val first = start
            val last = lastMovement
            if (first != null && last != null && distance > 0.0) {
                trips += Trip(first.capturedAtMs, last.capturedAtMs, first.odometerKm,
                    last.odometerKm, distance, first.socPercent, last.socPercent,
                    (activeZone ?: zoneId).id)
            }
            start = null
            lastMovement = null
            distance = 0.0
            activeZone = null
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
                if (start == null) {
                    start = previous
                    activeZone = previous.latitude?.let { lat -> previous.longitude?.let { lon ->
                        zoneForCoordinates(lat, lon)
                    } }
                }
                if (activeZone == null) {
                    activeZone = current.latitude?.let { lat -> current.longitude?.let { lon ->
                        zoneForCoordinates(lat, lon)
                    } }
                }
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
        val currentZone = samples.lastOrNull()?.let { sample ->
            sample.latitude?.let { lat -> sample.longitude?.let { lon -> zoneForCoordinates(lat, lon) } }
        } ?: zoneId
        val dayStartMs = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), currentZone)
            .atStartOfDay(currentZone).toInstant().toEpochMilli()
        val distanceToday = samples.zipWithNext().sumOf { (previous, current) ->
            val elapsed = current.capturedAtMs - previous.capturedAtMs
            val delta = current.odometerKm - previous.odometerKm
            if (current.capturedAtMs >= dayStartMs && current.capturedAtMs <= nowMs &&
                previous.capturedAtMs >= dayStartMs && previous.online && current.online &&
                !previous.charging && !current.charging &&
                elapsed in 1..MAX_SAMPLE_GAP_MS && delta > 0.0 && delta <= MAX_PLAUSIBLE_INTERVAL_KM
            ) delta else 0.0
        }
        val lastChargeAtMs = samples.lastOrNull { it.charging }?.capturedAtMs
        val sinceLastCharge = lastChargeAtMs?.let { chargeAt ->
            orderedTrips.filter { it.startedAtMs >= chargeAt }.sumOf { it.distanceKm }
        }
        // Attribute each complete reconstructed trip to its local start date.
        // This avoids splitting or shifting a trip's totals across calendar days.
        val dailySummaries = orderedTrips
            .groupBy { trip ->
                val tripZone = java.time.ZoneId.of(trip.timeZoneId)
                java.time.Instant.ofEpochMilli(trip.startedAtMs).atZone(tripZone).toLocalDate() to trip.timeZoneId
            }
            .entries
            .sortedByDescending { it.key.first }
            .map { (dayKey, dayTrips) ->
                val (date, tripZoneId) = dayKey
                DailySummary(
                    date = date,
                    tripCount = dayTrips.size,
                    totalDistanceKm = dayTrips.sumOf { it.distanceKm },
                    totalObservedDurationMs = dayTrips.sumOf { it.observedDurationMs },
                    totalSocUsedPercent = dayTrips.sumOf { it.socUsedPercent },
                    timeZoneId = tripZoneId
                )
            }
        return Report(orderedTrips, excludedGaps, ignoredJumps, samples.size, distanceToday, sinceLastCharge, dailySummaries)
    }
}
