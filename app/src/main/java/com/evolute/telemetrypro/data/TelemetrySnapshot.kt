package com.evolute.telemetrypro.data

import java.io.IOException

data class TelemetrySnapshot(
    val soc: Double,
    val odometer: Double,
    val stockRange: Double?,
    val online: Boolean,
    val ready: Boolean?,
    val updatedAt: String?,
    val sensors: Map<String, String>
)

/** Invalid local MCP bridge credentials require the user to reconnect the bridge. */
class AuthenticationRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)
