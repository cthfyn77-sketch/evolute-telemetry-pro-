package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHistoryAnalyzerTest {
    private fun sample(t: Long, odo: Double, soc: Double = 90.0, online: Boolean = true) =
        TripHistorySample(t, odo, soc, online)

    @Test fun buildsTripFromObservedOdometerIncrements() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 10.0, 90.0), sample(31_000, 10.2, 89.0),
            sample(61_000, 10.5, 88.0)
        ))
        assertEquals(1, report.trips.size)
        assertEquals(0.5, report.trips.single().distanceKm, 0.0001)
        assertEquals(2.0, report.trips.single().socUsedPercent, 0.0001)
    }

    @Test fun telemetryGapIsNotCountedOrConnectedAcross() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 10.0), sample(31_000, 10.2),
            sample(300_000, 14.0), sample(330_000, 14.2)
        ))
        assertEquals(2, report.trips.size)
        assertEquals(0.2, report.trips[0].distanceKm, 0.0001)
        assertEquals(0.2, report.trips[1].distanceKm, 0.0001)
        assertEquals(1, report.excludedGaps)
    }

    @Test fun offlineSamplesBreakTripWithoutAddingDistance() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0), sample(31_000, 1.2),
            sample(61_000, 9.0, online = false), sample(91_000, 9.2)
        ))
        assertEquals(1, report.trips.size)
        assertEquals(0.2, report.trips.single().distanceKm, 0.0001)
    }

    @Test fun stationaryPauseUpToFiveMinutesCanRemainWithinTrip() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0), sample(31_000, 1.2), sample(61_000, 1.2),
            sample(91_000, 1.4)
        ))
        assertEquals(1, report.trips.size)
        assertEquals(0.4, report.trips.single().distanceKm, 0.0001)
    }

    @Test fun longStationaryPauseSplitsTrips() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0), sample(31_000, 1.2),
            sample(400_000, 1.2), sample(430_000, 1.4)
        ))
        assertEquals(2, report.trips.size)
    }

    @Test fun odometerRollbackAndLargeJumpAreNeverCounted() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 10.0), sample(31_000, 10.2),
            sample(61_000, 9.0), sample(91_000, 20.0),
            sample(121_000, 20.2)
        ))
        assertEquals(2, report.trips.size)
        assertEquals(1, report.ignoredOdometerJumps)
        assertEquals(0.4, report.trips.sumOf { it.distanceKm }, 0.0001)
    }

    @Test fun duplicateAndInvalidSamplesAreFiltered() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0), sample(1_000, 99.0),
            sample(31_000, 1.2), sample(61_000, Double.NaN)
        ))
        assertEquals(2, report.sampleCount)
        assertEquals(0.2, report.trips.single().distanceKm, 0.0001)
    }

    @Test fun emptyHistoryDoesNotInventTrips() {
        val report = TripHistoryAnalyzer.analyze(emptyList())
        assertTrue(report.trips.isEmpty())
        assertEquals(0, report.sampleCount)
    }
}
