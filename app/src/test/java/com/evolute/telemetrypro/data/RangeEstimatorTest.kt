package com.evolute.telemetrypro.data

import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    private fun sample(soc: Double, odo: Double, minute: Int, charging: Boolean = false) =
        RangeEstimator.Sample(odo, soc, minute * 60_000L, charging)

    @Test fun usesOemUntilEightCompleteKilometreIntervalsExist() {
        val estimator = RangeEstimator()
        (0..7).forEach { km -> estimator.add(sample(100.0 - km * 0.4, km.toDouble(), km)) }
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(97.2, 250.0)?.source)

        estimator.add(sample(96.8, 8.0, 8))
        val result = estimator.estimate(96.8, 250.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, result.source)
        assertEquals(242.0, result.rangeKm, 0.001)
        assertEquals(40.0, result.socPer100Km, 0.001)
        assertEquals(8.0, result.distanceWindowKm, 0.001)
    }

    @Test fun interpolatesSocAtWholeKilometreBoundaries() {
        val estimator = RangeEstimator()
        estimator.add(sample(100.0, 0.0, 0))
        estimator.add(sample(98.4, 4.0, 1))
        estimator.add(sample(96.8, 8.0, 2))
        val result = estimator.estimate(96.8, 180.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, result.source)
        assertEquals(242.0, result.rangeKm, 0.001)
        assertEquals(40.0, result.socPer100Km, 0.001)
    }

    @Test fun usesOnlyLatestEightValidIntervals() {
        val estimator = RangeEstimator()
        var soc = 100.0
        estimator.add(sample(soc, 0.0, 0))
        (1..9).forEach { km ->
            soc -= km.toDouble()
            estimator.add(sample(soc, km.toDouble(), km))
        }
        // The first 1% interval falls out; the latest eight average 5.5%/km.
        val result = estimator.estimate(55.0, 120.0)!!
        assertEquals(10.0, result.rangeKm, 0.001)
        assertEquals(550.0, result.socPer100Km, 0.001)
    }

    @Test fun updatesEstimateWithCurrentSocDuringPartialNextInterval() {
        val estimator = RangeEstimator()
        (0..8).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        val complete = estimator.estimate(96.0, 180.0)!!
        assertEquals(192.0, complete.rangeKm, 0.001)

        estimator.add(sample(95.75, 8.5, 9))
        val partial = estimator.estimate(95.75, 180.0)!!
        assertEquals(191.5, partial.rangeKm, 0.001)
    }

    @Test fun preservesLastValidWindowAcrossTelemetryGap() {
        val estimator = RangeEstimator()
        (0..8).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        estimator.add(sample(95.5, 8.5, 12))
        val result = estimator.estimate(95.5, 180.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, result.source)
        assertEquals(191.0, result.rangeKm, 0.001)
        assertEquals(50.0, result.socPer100Km, 0.001)
    }

    @Test fun doesNotCountMissingDistanceAfterTelemetryGap() {
        val estimator = RangeEstimator()
        (0..4).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        estimator.add(sample(98.0, 4.5, 10)) // gap: unfinished interval restarts here
        (1..8).forEach { km ->
            estimator.add(sample(98.0 - km * 0.4, 4.5 + km, 10 + km))
        }
        val result = estimator.estimate(94.8, 180.0)!!
        assertEquals(237.0, result.rangeKm, 0.001)
        assertEquals(40.0, result.socPer100Km, 0.001)
    }

    @Test fun recoversAfterLargeOdometerJumpFollowingTelemetryOutage() {
        val estimator = RangeEstimator()
        (0..8).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        // The restored reading is more than 5 km ahead, but follows a long outage.
        estimator.add(sample(90.0, 20.0, 20))
        (1..8).forEach { km ->
            estimator.add(sample(90.0 - km * 0.8, 20.0 + km, 20 + km))
        }
        val result = estimator.estimate(83.6, 180.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, result.source)
        assertEquals(104.5, result.rangeKm, 0.001)
        assertEquals(80.0, result.socPer100Km, 0.001)
    }

    @Test fun doesNotReusePreGapIntervalsUntilEightNewIntervalsExist() {
        val estimator = RangeEstimator()
        estimator.add(sample(100.0, 0.0, 0))
        estimator.add(sample(99.5, 1.0, 1))
        estimator.add(sample(99.3, 1.5, 10))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(99.3, 150.0)?.source)
    }

    @Test fun chargingStartsANewConsumptionWindow() {
        val estimator = RangeEstimator()
        (0..8).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        estimator.add(sample(100.0, 8.0, 20, charging = true))
        estimator.add(sample(100.0, 8.0, 21))
        (1..7).forEach { km -> estimator.add(sample(100.0 - km * 0.4, 8.0 + km, 21 + km)) }
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(97.2, 180.0)?.source)

        estimator.add(sample(96.8, 16.0, 29))
        val result = estimator.estimate(96.8, 180.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, result.source)
        assertEquals(242.0, result.rangeKm, 0.001)
    }

    @Test fun socRiseResetsTheRollingWindow() {
        val estimator = RangeEstimator()
        (0..4).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        estimator.add(sample(100.0, 4.5, 5))
        (1..8).forEach { km -> estimator.add(sample(100.0 - km * 0.4, 4.5 + km, 5 + km)) }
        val result = estimator.estimate(96.8, 180.0)!!
        assertEquals(242.0, result.rangeKm, 0.001)
        assertEquals(40.0, result.socPer100Km, 0.001)
    }

    @Test fun rejectsOdometerRollbackAndOutOfOrderSamples() {
        val estimator = RangeEstimator()
        estimator.add(sample(100.0, 10.0, 1))
        estimator.add(sample(99.0, 9.0, 2))
        estimator.add(sample(98.0, 11.0, 0))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(98.0, 140.0)?.source)
    }

    @Test fun rejectsInvalidSocAndLargeTelemetryJumps() {
        val estimator = RangeEstimator()
        estimator.add(sample(100.0, 0.0, 0))
        estimator.add(sample(101.0, 1.0, 1))
        estimator.add(sample(70.0, 1.0, 2))
        estimator.add(sample(99.0, 7.0, 3))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(99.0, 140.0)?.source)
    }

    @Test fun clearRemovesCollectedHistory() {
        val estimator = RangeEstimator()
        (0..8).forEach { km -> estimator.add(sample(100.0 - km * 0.5, km.toDouble(), km)) }
        assertEquals(RangeEstimator.Source.MEASURED_1_KM, estimator.estimate(96.0, 180.0)?.source)
        estimator.clear()
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(96.0, 180.0)?.source)
    }
}
