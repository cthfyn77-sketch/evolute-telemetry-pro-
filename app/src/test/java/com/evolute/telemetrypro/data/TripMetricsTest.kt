package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMetricsTest {
    private fun trip(
        distance: Double = 100.0,
        startSoc: Double = 80.0,
        endSoc: Double = 70.0,
        durationMs: Long = 3_600_000L
    ) = TripRecord(
        id = 1L,
        startedAtMs = 1_000L,
        endedAtMs = 1_000L + durationMs,
        startOdometer = 100.0,
        endOdometer = 100.0 + distance,
        startSoc = startSoc,
        endSoc = endSoc,
        startLat = null,
        startLon = null,
        endLat = null,
        endLon = null,
        lastMovementMs = 1_000L + durationMs,
        status = "completed"
    )

    @Test fun calculatesEnergyFrom52KwhBattery() {
        val result = TripMetrics.calculate(trip(startSoc = 80.0, endSoc = 70.0), emptyList())
        assertEquals(5.2, result.energyUsedKwh!!, 0.0001)
        assertEquals(5.2, result.consumptionKwhPer100Km!!, 0.0001)
    }

    @Test fun eightSocPointsEqual416Kwh() {
        val result = TripMetrics.calculate(trip(startSoc = 82.0, endSoc = 74.0), emptyList())
        assertEquals(4.16, result.energyUsedKwh!!, 0.0001)
    }

    @Test fun calculatesAverageSpeedFromTripDuration() {
        val result = TripMetrics.calculate(trip(distance = 60.0, durationMs = 2 * 3_600_000L), emptyList())
        assertEquals(30.0, result.averageSpeedKmh!!, 0.0001)
    }

    @Test fun calculatesTemperatureMinAverageAndMax() {
        val result = TripMetrics.calculate(trip(), listOf(20.0, 24.0, 28.0))
        assertEquals(20.0, result.minBatteryTempC!!, 0.0001)
        assertEquals(24.0, result.averageBatteryTempC!!, 0.0001)
        assertEquals(28.0, result.maxBatteryTempC!!, 0.0001)
        assertEquals(3, result.temperatureSamples)
    }

    @Test fun doesNotReportEnergyWhenSocIncreasedDuringTrip() {
        val result = TripMetrics.calculate(trip(startSoc = 70.0, endSoc = 75.0), emptyList())
        assertNull(result.energyUsedKwh)
        assertNull(result.consumptionKwhPer100Km)
    }
}
