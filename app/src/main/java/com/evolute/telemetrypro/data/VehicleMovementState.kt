package com.evolute.telemetrypro.data

/**
 * "In transit" requires ready=true and an observed odometer increase.
 * A stop keeps the state while ready remains true; an explicit unlocked
 * central-lock signal while stationary ends it. Missing lock data preserves
 * the previous state.
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

            // The API's ready flag is a hard prerequisite for "В пути".
            if (!sample.ready) {
                state = State.NOT_READY
                previous = sample
                continue
            }

            val prior = previous
            var moved = false
            var validInterval = false
            if (prior != null && prior.online && prior.ready) {
                val elapsed = sample.capturedAtMs - prior.capturedAtMs
                val delta = sample.odometerKm - prior.odometerKm
                validInterval = elapsed in 1..90_000
                moved = validInterval && delta > 0.0 && delta <= 5.0
            }

            if (moved) {
                state = State.IN_TRANSIT
            } else {
                when (sample.centralLockingOn) {
                    false -> {
                        if (state == State.IN_TRANSIT && validInterval) state = State.READY
                        else if (state == State.UNKNOWN || state == State.NOT_READY) state = State.READY
                    }
                    true -> if (state == State.UNKNOWN || state == State.NOT_READY) state = State.READY
                    null -> if (state == State.UNKNOWN || state == State.NOT_READY) state = State.READY
                }
            }
            previous = sample
        }
        return state
    }
}
