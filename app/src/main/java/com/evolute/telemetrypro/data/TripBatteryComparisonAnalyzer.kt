package com.evolute.telemetrypro.data

/** A complete local snapshot used to compare measured battery indicators across a trip. */
data class TripBatterySample(
    val capturedAtMs: Long,
    val socPercent: Double,
    val online: Boolean,
    val charging: Boolean,
    val sensorsJson: String
)

object TripBatteryComparisonAnalyzer {
    data class Comparison(
        val tripStartedAtMs: Long,
        val tripEndedAtMs: Long,
        val startSocPercent: Double,
        val endSocPercent: Double,
        val batteryTempStart: Double?,
        val batteryTempEnd: Double?,
        val auxiliaryVoltageStart: Double?,
        val auxiliaryVoltageEnd: Double?,
        val sampleCount: Int
    ) {
        val socChangePercent: Double get() = endSocPercent - startSocPercent
        val batteryTempChange: Double? get() =
            if (batteryTempStart != null && batteryTempEnd != null) batteryTempEnd - batteryTempStart else null
        val auxiliaryVoltageChange: Double? get() =
            if (auxiliaryVoltageStart != null && auxiliaryVoltageEnd != null)
                auxiliaryVoltageEnd - auxiliaryVoltageStart else null
    }

    data class Report(val comparisons: List<Comparison>) {
        companion object { fun empty() = Report(emptyList()) }
    }

    fun analyze(
        trips: List<TripHistoryAnalyzer.Trip>,
        input: List<TripBatterySample>
    ): Report {
        val samples = input.asSequence()
            .filter { it.capturedAtMs > 0L }
            .sortedBy { it.capturedAtMs }
            .distinctBy { it.capturedAtMs }
            .toList()
        val comparisons = trips.mapNotNull { trip ->
            val duringTrip = samples.filter {
                it.capturedAtMs in trip.startedAtMs..trip.endedAtMs
            }
            if (duringTrip.size < 2 || duringTrip.any {
                    !it.socPercent.isFinite() || it.socPercent !in 0.0..100.0 ||
                        !it.online || it.charging
                }) return@mapNotNull null
            if (duringTrip.zipWithNext().any {
                    it.second.capturedAtMs - it.first.capturedAtMs !in 1..TripHistoryAnalyzer.MAX_SAMPLE_GAP_MS
                }) return@mapNotNull null
            val first = duringTrip.first()
            val last = duringTrip.last()
            Comparison(
                tripStartedAtMs = trip.startedAtMs,
                tripEndedAtMs = trip.endedAtMs,
                startSocPercent = first.socPercent,
                endSocPercent = last.socPercent,
                batteryTempStart = sensor(first.sensorsJson, "batteryTemp"),
                batteryTempEnd = sensor(last.sensorsJson, "batteryTemp"),
                auxiliaryVoltageStart = sensor(first.sensorsJson, "12VBatteryVoltage"),
                auxiliaryVoltageEnd = sensor(last.sensorsJson, "12VBatteryVoltage"),
                sampleCount = duringTrip.size
            )
        }
        return Report(comparisons)
    }

    private fun sensor(raw: String, key: String): Double? {
        val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"?(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\\"?")
        return pattern.find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
    }
}
