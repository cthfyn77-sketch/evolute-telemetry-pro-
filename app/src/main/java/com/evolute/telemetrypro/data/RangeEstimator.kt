package com.evolute.telemetrypro.data

import kotlin.math.abs

/**
 * Estimates driving range from measured SOC consumption at whole-kilometre
 * odometer checkpoints. The latest eight valid 1-km intervals form the
 * rolling consumption window; OEM range is used until that window is ready.
 */
class RangeEstimator {
    data class Sample(
        val odometerKm: Double,
        val socPercent: Double,
        val capturedAtMillis: Long,
        val charging: Boolean = false
    )

    data class Estimate(
        val rangeKm: Double,
        val socPer100Km: Double,
        val distanceWindowKm: Double,
        val source: Source
    )

    enum class Source { MEASURED_1_KM, OEM_FALLBACK }

    private val samples = ArrayList<Sample>()
    private val maxSamples = 20_000

    @Synchronized
    fun add(sample: Sample) {
        if (!sample.odometerKm.isFinite() || !sample.socPercent.isFinite()) return
        if (sample.socPercent !in 0.0..100.0 || sample.odometerKm < 0.0) return

        val last = samples.lastOrNull()
        if (last != null) {
            if (sample.capturedAtMillis <= last.capturedAtMillis) return
            if (sample.odometerKm < last.odometerKm) return
            if (sample.odometerKm - last.odometerKm > MAX_SAMPLE_DISTANCE_KM) return
            if (abs(sample.socPercent - last.socPercent) > MAX_SOC_CHANGE_PERCENT) return
        }

        samples.add(sample)
        if (samples.size > maxSamples) samples.removeAt(0)
    }

    @Synchronized
    fun estimate(remainingSocPercent: Double, oemRangeKm: Double?): Estimate? {
        if (!remainingSocPercent.isFinite() || remainingSocPercent !in 0.0..100.0) return null

        // A charging sample closes the previous discharge history. Only
        // samples collected after the latest charging marker are considered.
        val lastCharge = samples.indexOfLast { it.charging }
        val segment = samples.drop(lastCharge + 1)
        if (segment.size < 2) return oemFallback(oemRangeKm)

        var baselineDistance = segment.first().odometerKm
        var baselineSoc = segment.first().socPercent
        var nextCheckpoint = baselineDistance + STEP_KM
        val validIntervals = mutableListOf<Double>()

        for (index in 1 until segment.size) {
            val previous = segment[index - 1]
            val current = segment[index]
            val distance = current.odometerKm - previous.odometerKm

            // Do not infer consumption or distance across a telemetry outage.
            // Keep the last valid rolling window, but restart the unfinished
            // interval at the first sample received after the outage.
            if (current.capturedAtMillis - previous.capturedAtMillis > TELEMETRY_GAP_MILLIS) {
                baselineDistance = current.odometerKm
                baselineSoc = current.socPercent
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }

            // A SOC rise indicates charging/regeneration or a sensor reset.
            // Do not mix it with discharge consumption.
            if (current.socPercent - previous.socPercent >= SOC_RISE_RESET_PERCENT) {
                validIntervals.clear()
                baselineDistance = current.odometerKm
                baselineSoc = current.socPercent
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }

            if (distance < -EPSILON) {
                validIntervals.clear()
                baselineDistance = current.odometerKm
                baselineSoc = current.socPercent
                nextCheckpoint = baselineDistance + STEP_KM
                continue
            }
            if (distance <= EPSILON) continue

            while (nextCheckpoint <= current.odometerKm + EPSILON) {
                if (nextCheckpoint > previous.odometerKm + EPSILON) {
                    val fraction = ((nextCheckpoint - previous.odometerKm) / distance).coerceIn(0.0, 1.0)
                    val checkpointSoc = previous.socPercent +
                        (current.socPercent - previous.socPercent) * fraction
                    val consumed = baselineSoc - checkpointSoc

                    // Invalid intervals are discarded; they are never joined
                    // with a later interval to manufacture a longer sample.
                    if (consumed > EPSILON && consumed.isFinite() &&
                        baselineSoc in 0.0..100.0 && checkpointSoc in 0.0..100.0
                    ) {
                        validIntervals.add(consumed / STEP_KM)
                        if (validIntervals.size > MAX_VALID_INTERVALS) {
                            validIntervals.removeAt(0)
                        }
                    }
                    baselineDistance = nextCheckpoint
                    baselineSoc = checkpointSoc
                }
                nextCheckpoint += STEP_KM
            }
        }

        if (validIntervals.size < MAX_VALID_INTERVALS) return oemFallback(oemRangeKm)

        val averagePerKm = validIntervals.average()
        if (!averagePerKm.isFinite() || averagePerKm <= EPSILON) return oemFallback(oemRangeKm)

        val range = remainingSocPercent / averagePerKm
        val consumptionPer100Km = averagePerKm * 100.0
        if (!range.isFinite() || range < 0.0 || !consumptionPer100Km.isFinite()) {
            return oemFallback(oemRangeKm)
        }

        return Estimate(
            rangeKm = range,
            socPer100Km = consumptionPer100Km,
            distanceWindowKm = MAX_VALID_INTERVALS.toDouble(),
            source = Source.MEASURED_1_KM
        )
    }

    private fun oemFallback(oemRangeKm: Double?): Estimate? =
        oemRangeKm?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            Estimate(it, Double.NaN, 0.0, Source.OEM_FALLBACK)
        }

    @Synchronized
    fun clear() = samples.clear()

    private companion object {
        const val STEP_KM = 1.0
        const val MAX_VALID_INTERVALS = 8
        const val EPSILON = 0.0001
        const val SOC_RISE_RESET_PERCENT = 0.2
        const val TELEMETRY_GAP_MILLIS = 3 * 60 * 1000L
        const val MAX_SAMPLE_DISTANCE_KM = 5.0
        const val MAX_SOC_CHANGE_PERCENT = 20.0
    }
}
