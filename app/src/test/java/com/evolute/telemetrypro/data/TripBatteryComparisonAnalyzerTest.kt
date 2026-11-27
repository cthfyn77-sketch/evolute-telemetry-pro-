package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripBatteryComparisonAnalyzerTest {
    private fun trip() = TripHistoryAnalyzer.Trip(
        startedAtMs = 1_000, endedAtMs = 61_000,
        startOdometerKm = 100.0, endOdometerKm = 102.0,
        distanceKm = 2.0, startSocPercent = 80.0, endSocPercent = 78.0
    )
    private fun sample(
        time: Long, soc: Double, online: Boolean = true, charging: Boolean = false,
        temp: String = "25.0", voltage: String = "12.6"
    ) = TripBatterySample(time, soc, online, charging,
        """{"batteryTemp":$temp,"12VBatteryVoltage":$voltage}""")

    @Test fun comparesMeasuredSocTemperatureAndAuxiliaryVoltageAcrossTrip() {
        val report = TripBatteryComparisonAnalyzer.analyze(listOf(trip()), listOf(
            sample(1_000, 80.0, temp = "22.0", voltage = "12.8"),
            sample(31_000, 79.0, temp = "24.0", voltage = "12.5"),
            sample(61_000, 78.0, temp = "27.0", voltage = "12.3")
        ))
        val comparison = report.comparisons.single()
        assertEquals(-2.0, comparison.socChangePercent, 0.0001)
        assertEquals(22.0, comparison.batteryTempStart!!, 0.0001)
        assertEquals(27.0, comparison.batteryTempEnd!!, 0.0001)
        assertEquals(5.0, comparison.batteryTempChange!!, 0.0001)
        assertEquals(12.8, comparison.auxiliaryVoltageStart!!, 0.0001)
        assertEquals(12.3, comparison.auxiliaryVoltageEnd!!, 0.0001)
        assertEquals(-0.5, comparison.auxiliaryVoltageChange!!, 0.0001)
        assertEquals(3, comparison.sampleCount)
        assertEquals(3, comparison.points.size)
        assertEquals(79.0, comparison.points[1].socPercent, 0.0001)
        assertEquals(24.0, comparison.points[1].batteryTempC!!, 0.0001)
        assertEquals(12.5, comparison.points[1].auxiliaryVoltageV!!, 0.0001)
        assertEquals(31_000L, comparison.points[1].capturedAtMs)
    }

    @Test fun ignoresOfflineAndChargingSnapshots() {
        val report = TripBatteryComparisonAnalyzer.analyze(listOf(trip()), listOf(
            sample(1_000, 80.0),
            sample(31_000, 79.0, online = false),
            sample(46_000, 78.5, charging = true),
            sample(61_000, 78.0)
        ))
        assertNull(report.comparisons.singleOrNull())
    }

    @Test fun doesNotInventSensorValuesWhenMissingOrInvalid() {
        val report = TripBatteryComparisonAnalyzer.analyze(listOf(trip()), listOf(
            TripBatterySample(1_000, 80.0, true, false, "{}"),
            TripBatterySample(61_000, 78.0, true, false,
                """{"batteryTemp":"unknown","12VBatteryVoltage":null}""")
        ))
        val comparison = report.comparisons.single()
        assertNull(comparison.batteryTempStart)
        assertNull(comparison.batteryTempEnd)
        assertNull(comparison.auxiliaryVoltageStart)
        assertNull(comparison.auxiliaryVoltageEnd)
    }

    @Test fun requiresAtLeastTwoValidSnapshotsWithinTrip() {
        val report = TripBatteryComparisonAnalyzer.analyze(listOf(trip()), listOf(
            sample(1_000, 80.0), sample(90_000, 78.0)
        ))
        assertNull(report.comparisons.singleOrNull())
    }

    @Test fun invalidSocAndDuplicateTimestampsAreFiltered() {
        val report = TripBatteryComparisonAnalyzer.analyze(listOf(trip()), listOf(
            sample(1_000, 80.0), sample(1_000, 50.0),
            sample(61_000, Double.NaN)
        ))
        assertNull(report.comparisons.singleOrNull())
    }
}
