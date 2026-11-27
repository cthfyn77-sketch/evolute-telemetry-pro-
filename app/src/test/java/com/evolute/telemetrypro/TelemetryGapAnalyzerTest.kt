package com.evolute.telemetrypro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryGapAnalyzerTest {
    @Test
    fun regularThirtySecondSamplesDoNotCreateGaps() {
        val now = 1_000_000L
        val report = TelemetryGapAnalyzer.analyze(
            listOf(now - 60_000L, now - 30_000L, now),
            now
        )
        assertEquals(3, report.sampleCount)
        assertEquals(0, report.gapsOverThreshold)
        assertEquals(30_000L, report.longestGapMs)
        assertEquals(30_000L, report.latestGapMs)
    }

    @Test
    fun intervalsLongerThanNinetySecondsAreCountedAsMissedSamples() {
        val now = 1_000_000L
        val report = TelemetryGapAnalyzer.analyze(
            listOf(now - 300_000L, now - 270_000L, now - 90_000L, now),
            now
        )
        assertEquals(2, report.gapsOverThreshold)
        assertEquals(180_000L, report.longestGapMs)
        assertEquals(90_000L, report.latestGapMs)
    }

    @Test
    fun timestampsAreSortedAndDuplicatesAreIgnored() {
        val now = 1_000_000L
        val report = TelemetryGapAnalyzer.analyze(
            listOf(now, now - 30_000L, now - 30_000L, now - 60_000L),
            now
        )
        assertEquals(3, report.sampleCount)
        assertEquals(30_000L, report.longestGapMs)
    }

    @Test
    fun timestampsOutsideTheTwentyFourHourWindowAndFutureTimesAreIgnored() {
        val now = 2_000_000L
        val report = TelemetryGapAnalyzer.analyze(
            listOf(now - TelemetryGapAnalyzer.WINDOW_MS - 1L, now - 1_000L, now + 1L),
            now
        )
        assertEquals(1, report.sampleCount)
        assertNull(report.longestGapMs)
        assertNull(report.latestGapMs)
    }

    @Test
    fun emptyAndSingleSampleHistoriesHaveNoCalculatedGap() {
        val now = 1_000_000L
        val empty = TelemetryGapAnalyzer.analyze(emptyList(), now)
        assertEquals(0, empty.sampleCount)
        assertNull(empty.longestGapMs)

        val single = TelemetryGapAnalyzer.analyze(listOf(now), now)
        assertEquals(1, single.sampleCount)
        assertNull(single.longestGapMs)
    }

    @Test
    fun durationsAreFormattedForDashboard() {
        assertEquals("45 с", TelemetryGapAnalyzer.durationLabel(45_000L))
        assertEquals("2 мин 5 с", TelemetryGapAnalyzer.durationLabel(125_000L))
        assertEquals("1 ч 2 мин", TelemetryGapAnalyzer.durationLabel(3_720_000L))
    }
}
