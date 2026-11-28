package com.evolute.telemetrypro.data

import org.junit.Assert.*
import org.junit.Test

class TripRouteGeometryTest {
    @Test fun routeDistanceRejectsNearDuplicatePoints() {
        val meters = distance(48.8566, 2.3522, 48.85661, 2.35221)
        assertTrue(meters < 5.0)
    }

    @Test fun routeDistanceAcceptsMeaningfulMovement() {
        val meters = distance(48.8566, 2.3522, 48.8570, 2.3522)
        assertTrue(meters > 30.0)
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 6_371_000.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
}
