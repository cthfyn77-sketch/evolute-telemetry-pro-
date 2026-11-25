package com.evolute.telemetrypro.data

import kotlin.math.abs

/** Uses a continuous, non-charging telemetry segment and the most recent ~10 km. */
class RangeEstimator {
    data class Sample(val odometerKm: Double, val socPercent: Double, val capturedAtMillis: Long, val charging: Boolean = false)
    data class Estimate(val rangeKm: Double, val socPer100Km: Double, val distanceWindowKm: Double, val source: Source)
    enum class Source { MEASURED_10_KM, OEM_FALLBACK }

    private val samples = ArrayList<Sample>()
    private val maxSamples = 20_000

    @Synchronized
    fun add(sample: Sample) {
        if (!sample.odometerKm.isFinite() || !sample.socPercent.isFinite()) return
        if (sample.socPercent !in 0.0..100.0 || sample.odometerKm < 0.0) return
        val last = samples.lastOrNull()
        if (last != null) {
            if (sample.capturedAtMillis <= last.capturedAtMillis) return
            if (sample.odometerKm < last.odometerKm || sample.odometerKm - last.odometerKm > 5.0) return
            if (abs(sample.socPercent - last.socPercent) > 20.0) return
        }
        samples.add(sample)
        if (samples.size > maxSamples) samples.removeAt(0)
    }

    @Synchronized
    fun estimate(remainingSocPercent: Double, oemRangeKm: Double?): Estimate? {
        if (remainingSocPercent !in 0.0..100.0) return null
        // Charging is a hard boundary: never calculate consumption across a charge session.
        val lastChargeIndex = samples.indexOfLast { it.charging }
        val segment = samples.drop(lastChargeIndex + 1).filterNot { it.charging }
        val latest = segment.lastOrNull()
        if (latest != null) {
            val start = segment.lastOrNull { it.odometerKm <= latest.odometerKm - 10.0 + 1e-6 }
            if (start != null) {
                val distance = latest.odometerKm - start.odometerKm
                val consumed = start.socPercent - latest.socPercent
                if (distance >= 9.5 && consumed > 0.0 && consumed <= 50.0) {
                    val per100 = consumed / distance * 100.0
                    if (per100 in 0.1..100.0) {
                        return Estimate(remainingSocPercent / per100 * 100.0, per100, distance, Source.MEASURED_10_KM)
                    }
                }
            }
        }
        return oemRangeKm?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            Estimate(it, Double.NaN, 0.0, Source.OEM_FALLBACK)
        }
    }

    @Synchronized
    fun clear() = samples.clear()
}
