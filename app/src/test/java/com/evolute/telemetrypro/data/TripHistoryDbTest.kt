package com.evolute.telemetrypro.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TripHistoryDbTest {
    @Test fun storesAndFinishesTripWithoutChangingSampleHistory() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("evolute_telemetry_pro.sqlite")
        val db = SnapshotDb.get(context)

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
