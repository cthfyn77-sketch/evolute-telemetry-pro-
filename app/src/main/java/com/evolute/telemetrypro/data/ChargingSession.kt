package com.evolute.telemetrypro.data

data class ChargingSession(
    val id: Long,
    val cableInsertedAtMs: Long,
    val energyStartedAtMs: Long?,
    val energyStoppedAtMs: Long?,
    val cableRemovedAtMs: Long?,
    val cableInsertedSoc: Double?,
    val socBeforeEnergy: Double?,
    val socAfterEnergy: Double?,
    val batteryTempBefore: Double?,
    val batteryTempAfter: Double?,
    val energyDurationMs: Long,
    val hasEnergyStateSensor: Boolean
) {
    val startedAtMs: Long get() = cableInsertedAtMs
    val endedAtMs: Long? get() = cableRemovedAtMs
    val startSoc: Double? get() = socBeforeEnergy ?: cableInsertedSoc
    val endSoc: Double? get() = socAfterEnergy
    val durationMs: Long? get() = cableRemovedAtMs?.let { (it - cableInsertedAtMs).coerceAtLeast(0L) }
    val socGain: Double? get() = if (energyStartedAtMs == null) 0.0
        else if (socBeforeEnergy != null && socAfterEnergy != null) socAfterEnergy - socBeforeEnergy else null
}
