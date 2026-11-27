package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryHistoryAnalyzerTest {
    private fun row(second: Int, json: String, soc: Double = 70.0) =
        BatteryHistorySample("2026-11-27T12:00:${second.toString().padStart(2, '0')}Z", soc, 100.0, json)

    @Test fun summarizesNestedBatteryMetricsAndSnapshotSoc() {
        val report = BatteryHistoryAnalyzer.analyze(listOf(
            row(0, """{"battery":{"voltage":350.0,"temperature":24}}""", 80.0),
            row(30, """{"battery":{"voltage":351.0,"temperature":25}}""", 79.0)
        ))
        assertEquals(3, report.metrics.size)
        assertEquals(351.0, report.metrics.single { it.path == "battery.voltage" }.latest, 0.001)
        assertEquals(79.0, report.metrics.single { it.path == "SOC" }.latest, 0.001)
    }

    @Test fun filtersOnlyAnIsolatedSpikeThatReturnsToBaseline() {
        val report = BatteryHistoryAnalyzer.analyze(listOf(
            row(0, """{"batteryVoltage":350}"""),
            row(30, """{"batteryVoltage":500}"""),
            row(60, """{"batteryVoltage":351}""")
        ))
        val metric = report.metrics.single()
        assertEquals(2, metric.samples)
        assertEquals(351.0, metric.max, 0.001)
        assertTrue(report.alerts.any { it.contains("одиночный скачок") })
    }

    @Test fun retainsSustainedChangesAndDoesNotBridgeLargeGaps() {
        val report = BatteryHistoryAnalyzer.analyze(listOf(
            row(0, """{"batteryVoltage":350}"""),
            BatteryHistorySample("2026-11-27T12:10:01Z", 70.0, 100.0, """{"batteryVoltage":420}"""),
            BatteryHistorySample("2026-11-27T12:10:30Z", 70.0, 100.0, """{"batteryVoltage":421}""")
        ))
        val metric = report.metrics.single()
        assertEquals(3, metric.samples)
        assertEquals(421.0, metric.latest, 0.001)
        assertTrue(BatteryHistoryAnalyzer.hasTelemetryGap(metric.points))
        assertTrue(report.alerts.isEmpty())
    }

    @Test fun usesSensorSocWithoutDuplicatingSnapshotSoc() {
        val report = BatteryHistoryAnalyzer.analyze(listOf(
            row(0, """{"soc":80,"batteryVoltage":350}""", 80.0),
            row(30, """{"soc":79,"batteryVoltage":351}""", 79.0)
        ))
        assertEquals(1, report.metrics.count { it.path.equals("soc", true) })
        assertEquals(2, report.metrics.single { it.path == "soc" }.samples)
    }

    @Test fun ignoresMalformedTimestampsAndInvalidSoc() {
        val report = BatteryHistoryAnalyzer.analyze(listOf(
            BatteryHistorySample("not-a-date", 70.0, 100.0, """{"batteryVoltage":350}"""),
            BatteryHistorySample("2026-11-27T12:00:30Z", 150.0, 100.0, """{}""")
        ))
        assertTrue(report.metrics.isEmpty())
    }
}
