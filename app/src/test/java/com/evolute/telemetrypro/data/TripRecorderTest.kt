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
        var lat = 55.75
        var now = System.currentTimeMillis()

        fun telemetry(ready: Boolean, charging: Boolean = false) = ApiClient.Telemetry(
            soc, odo, null, true, ready, null,
            mapOf("lat" to lat.toString(), "lon" to "37.61",
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

        // GPS-only movement: one displaced snapshot is insufficient; the
        // next consecutive snapshot beyond 500 m confirms the trip start.
        now = System.currentTimeMillis()
        lat = 55.75
        db.insert(telemetry(ready = true), false)
        val gpsBaselineCapturedAt = db.recentTripSamples(1).single().capturedAtMs
        now += 30_000
        lat = 55.755
        sample(ready = true)
        assertNull(db.activeTrip())
        now += 30_000
        sample(ready = true)
        assertNotNull(db.activeTrip())
        assertEquals(100.3, db.activeTrip()?.startOdometer ?: -1.0, 0.001)
        assertEquals(gpsBaselineCapturedAt, db.activeTrip()?.startedAtMs ?: -1L)

        // Finish that trip, then prove that READY OFF and a plugged-in
        // snapshot break the GPS confirmation sequence.
        db.finishTrip(db.activeTrip()!!.id, now)
        lat = 55.755
        now = System.currentTimeMillis()
        db.insert(telemetry(ready = true), false) // new stationary baseline
        lat = 55.760
        now = System.currentTimeMillis()
        sample(ready = true) // first displaced snapshot
        assertNull(db.activeTrip())
        now = System.currentTimeMillis()
        sample(ready = false) // READY OFF breaks the sequence
        now = System.currentTimeMillis()
        sample(ready = true) // cannot combine with the old displaced point
        assertNull(db.activeTrip())
        now = System.currentTimeMillis()
        sample(ready = true, charging = true) // plugged-in snapshot breaks it
        now = System.currentTimeMillis()
        sample(ready = true) // one snapshot after unplug is insufficient
        assertNull(db.activeTrip())
        now = System.currentTimeMillis()
        sample(ready = true) // second consecutive eligible point confirms movement
        assertNotNull(db.activeTrip())
    }
}