package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingEfficiencyAnalyzerTest {
    private fun sample(
        time: Long, odo: Double, soc: Double, online: Boolean = true, charging: Boolean = false
    ) = TripHistorySample(time, odo, soc, online, charging)

    @Test fun calculatesNetSocConsumptionPerHundredKm() {
        val report = DrivingEfficiencyAnalyzer.analyze(listOf(
            sample(1_000, 100.0, 80.0),
            sample(31_000, 102.0, 79.0),
            sample(61_000, 105.0, 77.5)
        ))
        assertEquals(5.0, report.distanceKm, 0.0001)
        assertEquals(2.5, report.socConsumedPercent, 0.0001)
        assertEquals(50.0, report.socPer100Km!!, 0.0001)
        assertEquals(2.0, report.kmPerSocPercent!!, 0.0001)
        assertEquals(2, report.intervalCount)
    }

    @Test fun regenerativeSocRiseOffsetsNetConsumption() {
        val report = DrivingEfficiencyAnalyzer.analyze(listOf(
            sample(1_000, 100.0, 70.0),
            sample(31_000, 102.0, 71.0),
            sample(61_000, 104.0, 69.0)
        ))
        assertEquals(4.0, report.distanceKm, 0.0001)
        assertEquals(1.0, report.socConsumedPercent, 0.0001)
        assertEquals(25.0, report.socPer100Km!!, 0.0001)
    }

    @Test fun excludesOfflineChargingAndTelemetryGapIntervals() {
        val report = DrivingEfficiencyAnalyzer.analyze(listOf(
            sample(1_000, 100.0, 80.0),
            sample(31_000, 101.0, 79.5),
            sample(61_000, 102.0, 79.0, charging = true),
            sample(91_000, 103.0, 78.5),
            sample(200_000, 104.0, 78.0),
            sample(230_000, 105.0, 77.5, online = false),
            sample(260_000, 106.0, 77.0)
        ))
        assertEquals(1.0, report.distanceKm, 0.0001)
        assertEquals(0.5, report.socConsumedPercent, 0.0001)
        assertEquals(1, report.intervalCount)
        assertEquals(5, report.excludedIntervalCount)
    }

    @Test fun rejectsImplausibleDistanceAndSocJumps() {
        val report = DrivingEfficiencyAnalyzer.analyze(listOf(
            sample(1_000, 100.0, 80.0),
            sample(31_000, 110.0, 79.0),
            sample(61_000, 111.0, 60.0),
            sample(91_000, 112.0, 59.5)
        ))
        assertEquals(1.0, report.distanceKm, 0.0001)
        assertEquals(0.5, report.socConsumedPercent, 0.0001)
        assertEquals(1, report.intervalCount)
        assertEquals(2, report.excludedIntervalCount)
    }

    @Test fun noValidDistanceProducesNoEfficiencyEstimate() {
        val report = DrivingEfficiencyAnalyzer.analyze(listOf(
            sample(1_000, 100.0, 80.0),
            sample(31_000, 100.0, 79.0)
        ))
        assertNull(report.socPer100Km)
        assertEquals(0, report.intervalCount)
    }
}
