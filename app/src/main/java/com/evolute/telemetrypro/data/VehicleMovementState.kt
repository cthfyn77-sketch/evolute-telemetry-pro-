package com.evolute.telemetrypro.data

/**
 * Conservative vehicle state reconstruction.
 * Once movement is observed, a stop does not end the trip state: only an
 * explicit unlocked central-lock signal clears it. Missing lock data preserves
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
            val prior = previous
            if (prior != null) {
                val elapsed = sample.capturedAtMs - prior.capturedAtMs
                val delta = sample.odometerKm - prior.odometerKm
                if (elapsed in 1..90_000 && delta > 0.0 && delta <= 5.0) {
                    state = State.IN_TRANSIT
                }
            }
            when (sample.centralLockingOn) {
                false -> state = State.READY
                true -> if (state == State.UNKNOWN && sample.ready) state = State.READY
                null -> if (state == State.UNKNOWN && !sample.ready) state = State.NOT_READY
            }
            previous = sample
        }
        return state
    }
}
