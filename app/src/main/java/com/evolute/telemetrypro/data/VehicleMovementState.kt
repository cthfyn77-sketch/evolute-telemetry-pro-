package com.evolute.telemetrypro.data

/**
 * Vehicle state is based exclusively on the API "ready" flag.
 * ready=true means "В пути"; ready=false means "Не готов к движению".
 * No door-lock or odometer signals participate in this decision.
 */
object VehicleMovementState {
    data class Sample(
        val capturedAtMs: Long,
        val online: Boolean,
        val ready: Boolean
    )

    enum class State { IN_TRANSIT, NOT_READY, UNKNOWN }

    fun analyze(samples: List<Sample>): State {
        val latest = samples.asSequence()
            .filter { it.online }
            .maxByOrNull { it.capturedAtMs }
            ?: return State.UNKNOWN
        return if (latest.ready) State.IN_TRANSIT else State.NOT_READY
    }
}
