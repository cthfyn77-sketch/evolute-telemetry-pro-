package com.evolute.telemetrypro.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TripHistoryDbTest {
    @After fun closeDatabase() {
        SnapshotDb.get(RuntimeEnvironment.getApplication()).close()
    }


    @Test fun storesRoutePointsAndRejectsDuplicateOrFinishedTripPoints() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        db.writableDatabase.delete("trip_route_points", null, null)
        db.writableDatabase.delete("trips", null, null)
        val id = db.startTrip(1_000L, 125.0, 88.0, 48.8566, 2.3522)
        assertTrue(db.insertTripRoutePoint(id, 1_000L, 48.8566, 2.3522, 5.0, 0.0))
        assertFalse(db.insertTripRoutePoint(id, 6_000L, 48.85661, 2.35221, 5.0, 0.0))
        assertTrue(db.insertTripRoutePoint(id, 11_000L, 48.8570, 2.3522, 5.0, 3.0))
        assertEquals(2, db.tripRoutePoints(id).size)
        db.finishTrip(id, 20_000L)
        assertFalse(db.insertTripRoutePoint(id, 25_000L, 48.8580, 2.3522, 5.0, 3.0))
    }

    @Test fun storesAndFinishesTripWithoutChangingSampleHistory() {
        val context = RuntimeEnvironment.getApplication()
        val db = SnapshotDb.get(context)
        db.writableDatabase.delete("trips", null, null)
        db.writableDatabase.delete("samples", null, null)

        val id = db.startTrip(1_000L, 125.0, 88.0, null, null)
        assertEquals(id, db.activeTrip()?.id)

        db.updateTrip(id, 137.4, 82.0, 52.0, 13.4, 2_000L)
        db.finishTrip(id, 3_000L)

        val trip = db.recentTrips().single()
        assertEquals(125.0, trip.startOdometer, 0.001)
        assertEquals(137.4, trip.endOdometer, 0.001)
        assertEquals(12.4, trip.distanceKm, 0.001)
        assertEquals(6.0, trip.socUsed, 0.001)
        assertEquals(3_000L, trip.endedAtMs)
        assertEquals("completed", trip.status)
        assertNull(db.activeTrip())
        assertEquals(0L, db.count())
    }
}
