package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleMovementStateTest {
    private fun sample(
        time: Long, odo: Double, locked: Boolean?, online: Boolean = true, ready: Boolean = true
    ) = VehicleMovementState.Sample(time, odo, online, ready, locked)

    @Test fun movementChangesStateToInTransit() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true)
        )))
    }

    @Test fun stopDoesNotClearTransitUntilDoorsUnlock() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true),
            sample(61_000, 10.1, true),
            sample(91_000, 10.1, true)
        )))
        assertEquals(VehicleMovementState.State.READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true),
            sample(61_000, 10.1, false)
        )))
    }

    @Test fun unlockingDuringMovementDoesNotClearTransit() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, false)
        )))
    }

    @Test fun missingLockSignalPreservesTransitState() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true),
            sample(61_000, 10.1, null)
        )))
    }

    @Test fun offlineSamplesDoNotCreateMovementOrUnlockTransition() {
        assertEquals(VehicleMovementState.State.IN_TRANSIT, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true),
            sample(61_000, 10.1, false, online = false)
        )))
    }

    @Test fun staleIntervalsDoNotCreateMovementOrClearTransit() {
        assertEquals(VehicleMovementState.State.READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(200_000, 10.1, false)
        )))
    }


    @Test fun movementWithoutReadyFlagNeverShowsInTransit() {
        assertEquals(VehicleMovementState.State.NOT_READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true, ready = false),
            sample(31_000, 10.1, true, ready = false)
        )))
    }

    @Test fun readyTurningOffImmediatelyClearsInTransitLabel() {
        assertEquals(VehicleMovementState.State.NOT_READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true),
            sample(31_000, 10.1, true),
            sample(61_000, 10.1, true, ready = false)
        )))
    }

    @Test fun readyTurningOnAtRestShowsReadyUntilMovementObserved() {
        assertEquals(VehicleMovementState.State.READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true, ready = false),
            sample(31_000, 10.0, true, ready = true)
        )))
    }

    @Test fun initialReadyAndLockedStateIsReady() {
        assertEquals(VehicleMovementState.State.READY, VehicleMovementState.analyze(listOf(
            sample(1_000, 10.0, true)
        )))
    }
}
