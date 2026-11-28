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

/** Legacy MCP client exception retained until the unused bridge implementation is removed. */
class AuthenticationRequiredException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
