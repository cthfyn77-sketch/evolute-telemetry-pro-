package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHistoryAnalyzerTest {
    private fun sample(t: Long, odo: Double, soc: Double = 90.0, online: Boolean = true, charging: Boolean = false) =
        TripHistorySample(t, odo, soc, online, charging)

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

    @Test fun reportSummarizesOnlyConfirmedTrips() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0, 90.0), sample(31_000, 1.2, 89.0),
            sample(61_000, 1.5, 88.0),
            sample(300_000, 5.0, 80.0), sample(330_000, 5.3, 77.0)
        ))
        assertEquals(2, report.trips.size)
        assertEquals(0.8, report.totalDistanceKm, 0.0001)
        assertEquals(5.0, report.totalSocUsedPercent, 0.0001)
        assertEquals(0.4, report.averageDistanceKm, 0.0001)
        assertEquals(0.5, report.longestTripKm, 0.0001)
    }

    @Test fun calculatesDistanceForCurrentLocalDay() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        val start = java.time.LocalDate.of(2026, 11, 27).atStartOfDay(zone).toInstant().toEpochMilli()
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(start - 120_000, 1.0), sample(start - 90_000, 1.2),
            sample(start + 30_000, 1.2), sample(start + 60_000, 1.5)
        ), nowMs = start + 90_000, zoneId = zone)
        assertEquals(0.3, report.distanceTodayKm, 0.0001)
    }

    @Test fun distanceSinceLastChargeStartsAfterChargingMarker() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 10.0, charging = true), sample(31_000, 10.0),
            sample(61_000, 10.2), sample(91_000, 10.4)
        ), nowMs = 100_000)
        assertEquals(0.4, report.distanceSinceLastChargeKm!!, 0.0001)
    }

    @Test fun missingChargingMarkerDoesNotInventSinceChargeDistance() {
        val report = TripHistoryAnalyzer.analyze(listOf(sample(1_000, 1.0), sample(31_000, 1.2)), nowMs = 100_000)
        assertEquals(null, report.distanceSinceLastChargeKm)
    }

    @Test fun chargingSampleSplitsTripAndDoesNotCountChargingDistance() {
        val report = TripHistoryAnalyzer.analyze(listOf(
            sample(1_000, 1.0), sample(31_000, 1.2), sample(61_000, 1.2, charging = true),
            sample(91_000, 1.5), sample(121_000, 1.7)
        ), nowMs = 130_000)
        assertEquals(2, report.trips.size)
        assertEquals(0.2, report.distanceSinceLastChargeKm!!, 0.0001)
    }

    @Test fun emptyHistoryDoesNotInventTrips() {
        val report = TripHistoryAnalyzer.analyze(emptyList())
        assertTrue(report.trips.isEmpty())
        assertEquals(0, report.sampleCount)
    }
}
