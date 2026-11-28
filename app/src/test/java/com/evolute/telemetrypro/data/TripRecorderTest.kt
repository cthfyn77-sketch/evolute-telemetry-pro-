package com.evolute.telemetrypro.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TripRecorderTest {
    @Test fun resumesSameTripAfterReadyOffAndDelayedChargingSession() {
        val context = RuntimeEnvironment.getApplication<Application>()
        context.deleteDatabase("evolute_telemetry_pro.sqlite")
        val db = SnapshotDb.get(context)
        val recorder = TripRecorder()
        var odo = 100.0
        var soc = 90.0
        var now = System.currentTimeMillis()

        fun telemetry(ready: Boolean, charging: Boolean = false) = ApiClient.Telemetry(
            soc, odo, null, true, ready, null,
            mapOf("lat" to "55.75", "lon" to "37.61",
                "isChargingGunInserted" to charging.toString())
        )
        fun sample(ready: Boolean, charging: Boolean = false) {
            val t = telemetry(ready, charging)
            recorder.record(db, t, charging, now)
            db.insert(t, charging)
        }

        // Initial stationary READY snapshot is the movement baseline.
        db.insert(telemetry(ready = true), false)
        now += 30_000
        odo += 0.1
        sample(ready = true)
        val tripId = db.activeTrip()?.id
        assertNotNull(tripId)

        // READY OFF starts the 10-minute grace period.
        now += 30_000
        sample(ready = false)
        assertEquals("waiting_ready_off", db.activeTrip()?.status)

        // Movement returns after eight minutes: it remains the same trip.
        now += 8 * 60_000
        odo += 0.1
        sample(ready = true)
        assertEquals(tripId, db.activeTrip()?.id)
        assertEquals("active", db.activeTrip()?.status)
        assertNull(db.activeTrip()?.readyOffSinceMs)

        // Plug insertion immediately enters charging wait, irrespective of
        // whether energy transfer has actually started.
        now += 30_000
        sample(ready = false, charging = true)
        assertEquals("charging", db.activeTrip()?.status)

        // Unplugging starts the 15-minute resume window.
        now += 60 * 60_000
        sample(ready = false, charging = false)
        assertEquals("waiting_after_charge", db.activeTrip()?.status)

        // READY and movement return within 15 minutes: same trip continues.
        now += 14 * 60_000
        odo += 0.1
        sample(ready = true)
        assertEquals(tripId, db.activeTrip()?.id)
        assertEquals("active", db.activeTrip()?.status)

        // Without charging, READY OFF closes the trip after ten minutes.
        now += 30_000
        sample(ready = false)
        now += 10 * 60_000 + 1
        sample(ready = false)
        assertNull(db.activeTrip())
        val finished = db.recentTrips().single()
        assertEquals(tripId, finished.id)
        assertEquals("completed", finished.status)
        assertNotNull(finished.endedAtMs)
    }
}