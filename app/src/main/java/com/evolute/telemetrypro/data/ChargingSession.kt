package com.evolute.telemetrypro.data

data class ChargingSession(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val startSoc: Double?,
    val endSoc: Double?
) {
    val durationMs: Long? get() = endedAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) }
    val socGain: Double? get() = if (startSoc != null && endSoc != null) endSoc - startSoc else null
}
