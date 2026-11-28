package com.evolute.telemetrypro.data

data class TelemetrySnapshot(
    val soc: Double,
    val odometer: Double,
    val stockRange: Double?,
    val online: Boolean,
    val ready: Boolean?,
    val updatedAt: String?,
    val sensors: Map<String, String>,
    /** Complete decoded EVACONNECT response, preserved as received (with sensitive IDs redacted). */
    val rawSensorsJson: String? = null
)
