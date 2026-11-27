package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingSessionAnalyzerTest {
    private fun sample(
        time: Long, soc: Double, charging: Boolean, online: Boolean = true
    ) = TripHistorySample(time, 100.0, soc, online, charging)

    @Test fun cableRemovalClosesSessionAndCapturesSocChange() {
        val report = ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 30.0, true),
            sample(31_000, 31.0, true),
            sample(61_000, 35.0, false)
        ))
        assertEquals(1, report.sessions.size)
        val session = report.sessions.single()
        assertEquals(ChargingSessionAnalyzer.Status.COMPLETED, session.status)
        assertEquals(30.0, session.startSocPercent, 0.0)
        assertEquals(35.0, session.endSocPercent, 0.0)
        assertEquals(2, session.sampleCount)
        assertEquals(5.0, report.totalSocIncreasePercent, 0.0)
    }

    @Test fun stillInsertedAtLatestSampleRemainsConnected() {
        val report = ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 40.0, true),
            sample(31_000, 42.0, true)
        ))
        assertEquals(ChargingSessionAnalyzer.Status.CONNECTED, report.sessions.single().status)
    }

    @Test fun telemetryGapClosesSessionAsInterrupted() {
        val report = ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 40.0, true),
            sample(31_000, 41.0, true),
            sample(200_000, 45.0, true)
        ))
        assertEquals(2, report.sessions.size)
        assertEquals(ChargingSessionAnalyzer.Status.INTERRUPTED, report.sessions[1].status)
        assertEquals(ChargingSessionAnalyzer.Status.CONNECTED, report.sessions[0].status)
    }

    @Test fun offlineSampleBreaksSessionWithoutPretendingItCompleted() {
        val report = ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 40.0, true),
            sample(31_000, 41.0, true),
            sample(61_000, 41.0, false, online = false)
        ))
        assertEquals(ChargingSessionAnalyzer.Status.INTERRUPTED, report.sessions.single().status)
    }

    @Test fun noCableInsertionDoesNotCreateSession() {
        assertEquals(0, ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 40.0, false),
            sample(31_000, 41.0, false)
        )).sessions.size)
    }

    @Test fun duplicateAndInvalidSocSamplesAreFiltered() {
        val report = ChargingSessionAnalyzer.analyze(listOf(
            sample(1_000, 40.0, true),
            sample(1_000, 41.0, true),
            sample(31_000, 120.0, false)
        ))
        assertEquals(1, report.sessions.size)
        assertEquals(1, report.sampleCount)
    }
}
