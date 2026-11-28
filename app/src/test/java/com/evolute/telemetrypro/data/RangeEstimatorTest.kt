package com.evolute.telemetrypro.data

import org.junit.Assert.*
import org.junit.Test

class RangeEstimatorTest {
    @Test fun usesOemUntilTenKilometresAreAvailable() {
        val estimator = RangeEstimator()
        estimator.add(RangeEstimator.Sample(100.0, 80.0, 1_000))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(80.0, 250.0)?.source)
    }

    @Test fun estimatesFromTenKmConsumption() {
        val estimator = RangeEstimator()
        estimator.add(RangeEstimator.Sample(100.0, 80.0, 1_000))
        estimator.add(RangeEstimator.Sample(105.0, 75.0, 2_000))
        estimator.add(RangeEstimator.Sample(110.0, 70.0, 3_000))
        val result = estimator.estimate(70.0, 200.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_10_KM, result.source)
        assertEquals(70.0, result.rangeKm, 0.001)
        assertEquals(100.0, result.socPer100Km, 0.001)
    }

    @Test fun interpolatesAtWholeKilometreCrossings() {
        val estimator = RangeEstimator()
        estimator.add(RangeEstimator.Sample(100.4, 80.0, 1_000))
        estimator.add(RangeEstimator.Sample(105.4, 75.0, 2_000))
        estimator.add(RangeEstimator.Sample(111.4, 69.0, 3_000))
        val result = estimator.estimate(69.0, 180.0)!!
        assertEquals(RangeEstimator.Source.MEASURED_10_KM, result.source)
        assertEquals(69.0, result.rangeKm, 0.001)
        assertEquals(10.0, result.distanceWindowKm, 0.001)
    }

    @Test fun chargingBreaksConsumptionWindow() {
        val estimator = RangeEstimator()
        estimator.add(RangeEstimator.Sample(100.0, 60.0, 1_000))
        estimator.add(RangeEstimator.Sample(105.0, 70.0, 2_000, charging = true))
        estimator.add(RangeEstimator.Sample(110.0, 55.0, 3_000))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(55.0, 180.0)?.source)
    }

    @Test fun rejectsOdometerRollbackAndLargeJump() {
        val estimator = RangeEstimator()
        estimator.add(RangeEstimator.Sample(100.0, 80.0, 1_000))
        estimator.add(RangeEstimator.Sample(99.0, 79.0, 2_000))
        estimator.add(RangeEstimator.Sample(110.0, 70.0, 3_000))
        assertEquals(RangeEstimator.Source.OEM_FALLBACK, estimator.estimate(70.0, 150.0)?.source)
    }
}
