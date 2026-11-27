package com.evolute.telemetrypro.data

/**
 * Conservative vehicle state reconstruction.
 * Once movement is observed, a stop does not end the trip state: only an
 * explicit unlocked central-lock signal received while stationary clears it.
 * Missing lock data preserves the previous state.
 */
object VehicleMovementState {
    data class Sample(
        val capturedAtMs: Long,
        val odometerKm: Double,
        val online: Boolean,
        val ready: Boolean,
        val centralLockingOn: Boolean?
    )

    enum class State { READY, IN_TRANSIT, NOT_READY, UNKNOWN }

    fun analyze(samples: List<Sample>): State {
        if (samples.isEmpty()) return State.UNKNOWN
        var state = State.UNKNOWN
        var previous: Sample? = null
        for (sample in samples.sortedBy { it.capturedAtMs }) {
            if (!sample.online) continue
            val prior = previous
            var moved = false
            var validInterval = false
            if (prior != null) {
                val elapsed = sample.capturedAtMs - prior.capturedAtMs
                val delta = sample.odometerKm - prior.odometerKm
                validInterval = elapsed in 1..90_000
                moved = validInterval && delta > 0.0 && delta <= 5.0
                if (moved) state = State.IN_TRANSIT
            }
            when (sample.centralLockingOn) {
                false -> {
                    if (state == State.UNKNOWN && sample.ready) state = State.READY
                    else if (state == State.IN_TRANSIT && validInterval && !moved) state = State.READY
                    else if (state == State.NOT_READY && sample.ready) state = State.READY
                }
                true -> if (state == State.UNKNOWN && sample.ready) state = State.READY
                null -> if (state == State.UNKNOWN && !sample.ready) state = State.NOT_READY
            }
            if (state == State.UNKNOWN && !sample.ready) state = State.NOT_READY
            previous = sample
        }
        return state
    }
}
