package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleMovementStateTest {
    private fun sample(time: Long, ready: Boolean, online: Boolean = true) =
        VehicleMovementState.Sample(time, online, ready)

    @Test fun readyTrueMeansInTransit() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT,
            VehicleMovementState.analyze(listOf(sample(1_000, true))))
    }

    @Test fun readyFalseMeansNotReady() {
        assertEquals(VehicleMovementState.State.NOT_READY,
            VehicleMovementState.analyze(listOf(sample(1_000, false))))
    }

    @Test fun latestOnlineReadyValueDeterminesState() {
        assertEquals(VehicleMovementState.State.NOT_READY, VehicleMovementState.analyze(listOf(
            sample(1_000, true),
            sample(31_000, false)
        )))
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, false),
            sample(31_000, true)
        )))
    }

    @Test fun offlineSamplesAreIgnored() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, true),
            sample(31_000, false, online = false)
        )))
    }

    @Test fun noOnlineSamplesMeansUnknown() {
        assertEquals(VehicleMovementState.State.UNKNOWN,
            VehicleMovementState.analyze(listOf(sample(1_000, true, online = false))))
    }

    @Test fun latestTimestampWinsEvenWhenInputIsUnordered() {
        assertEquals(VehicleMovementState.State.NOT_READY, VehicleMovementState.analyze(listOf(
            sample(31_000, false),
            sample(1_000, true)
        )))
    }
}
