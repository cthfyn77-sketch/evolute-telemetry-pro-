package com.evolute.telemetrypro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFreshnessTest {
    @Test
    fun recentSampleIsReportedAsFresh() {
        val now = 1_000_000L
        assertFalse(TelemetryFreshness.isStale(now - 30_000L, now))
        assertEquals("Последний снимок: 30 с назад", TelemetryFreshness.label(now - 30_000L, now))
    }

    @Test
    fun sampleOlderThanNinetySecondsShowsPossibleConnectionLoss() {
        val now = 1_000_000L
        assertTrue(TelemetryFreshness.isStale(now - 120_000L, now))
        assertTrue(TelemetryFreshness.label(now - 120_000L, now).contains("возможна потеря связи"))
    }

    @Test
    fun missingSampleIsNotPresentedAsFresh() {
        assertTrue(TelemetryFreshness.isStale(null, 1_000_000L))
        assertEquals("Ожидание первого снимка телеметрии", TelemetryFreshness.label(null, 1_000_000L))
    }

    @Test
    fun futureCaptureTimeIsClampedToZeroAge() {
        assertFalse(TelemetryFreshness.isStale(1_000_001L, 1_000_000L))
        assertEquals("Последний снимок: 0 с назад", TelemetryFreshness.label(1_000_001L, 1_000_000L))
    }
}
