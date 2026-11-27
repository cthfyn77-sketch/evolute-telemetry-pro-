package com.evolute.telemetrypro.data

import net.iakovlev.timeshape.TimeZoneEngine
import java.time.ZoneId

/**
 * Resolves the vehicle's IANA time zone from the GPS coordinates already present
 * in Evolute telemetry. The offline boundary database is initialized once per process.
 */
object VehicleTimeZoneResolver {
    private val engine: TimeZoneEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TimeZoneEngine.initialize()
    }

    fun zoneForCoordinates(latitude: Double, longitude: Double): ZoneId? {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0) return null
        return runCatching { engine.query(latitude, longitude).orElse(null) }.getOrNull()
    }
}
