package com.evolute.telemetrypro.data

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Stores raw telemetry snapshots and interpolates SOC at each whole-kilometre
 * odometer crossing. Charging creates a hard segment boundary.
 */
class RangeEstimator {
    data class Sample(val odometerKm: Double, val socPercent: Double, val capturedAtMillis: Long, val charging: Boolean = false)
    data class Estimate(val rangeKm: Double, val socPer100Km: Double, val distanceWindowKm: Double, val source: Source)
    enum class Source { MEASURED_10_KM, OEM_FALLBACK }
    private data class Marker(val km: Long, val soc: Double)

    private val samples = ArrayList<Sample>()
    private val maxSamples = 20_000

    @Synchronized fun add(sample: Sample) {
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

    @Synchronized fun estimate(remainingSocPercent: Double, oemRangeKm: Double?): Estimate? {
        if (remainingSocPercent !in 0.0..100.0) return null
        val lastCharge = samples.indexOfLast { it.charging }
        val segment = samples.drop(lastCharge + 1).filterNot { it.charging }
        val markers = interpolateKilometres(segment)
        val end = markers.lastOrNull()
        if (end != null) {
            val start = markers.lastOrNull { it.km == end.km - 10L }
            if (start != null) {
                val consumed = start.soc - end.soc
                if (consumed > 0.0 && consumed <= 50.0) {
                    val rawPer100 = consumed * 10.0
                    // Interpolation may produce 100.0000000000001 due to Double rounding.
                    val per100 = if (rawPer100 <= 100.0 + 1e-9) rawPer100.coerceAtMost(100.0) else rawPer100
                    if (per100 in 0.1..100.0) {
                        return Estimate(remainingSocPercent / per100 * 100.0, per100, 10.0, Source.MEASURED_10_KM)
                    }
                }
            }
        }
        return oemRangeKm?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            Estimate(it, Double.NaN, 0.0, Source.OEM_FALLBACK)
        }
    }

    private fun interpolateKilometres(segment: List<Sample>): List<Marker> {
        if (segment.size < 2) return emptyList()
        val result = ArrayList<Marker>()
        var nextKm = ceil(segment.first().odometerKm).toLong()
        for (i in 1 until segment.size) {
            val a = segment[i - 1]
            val b = segment[i]
            val distance = b.odometerKm - a.odometerKm
            if (distance <= 0.0 || distance > 5.0) continue
            while (nextKm.toDouble() <= b.odometerKm + 1e-9) {
                val target = nextKm.toDouble()
                if (target >= a.odometerKm - 1e-9) {
                    val fraction = ((target - a.odometerKm) / distance).coerceIn(0.0, 1.0)
                    val soc = a.socPercent + (b.socPercent - a.socPercent) * fraction
                    result.add(Marker(nextKm, soc))
                }
                nextKm++
            }
        }
        return result
    }

    @Synchronized fun clear() = samples.clear()
}
