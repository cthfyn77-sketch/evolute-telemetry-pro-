package com.evolute.telemetrypro.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChargingSessionTest {
    @After fun closeDatabase() {
        SnapshotDb.get(RuntimeEnvironment.getApplication()).close()
    }

    private fun telemetry(soc: Double) = ApiClient.Telemetry(
        soc, 100.0, null, true, false, null,
        mapOf("isChargingGunInserted" to "true")
    )

    @Test fun plugInsertionStartsOneSessionAndUnplugFinishesIt() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        db.writableDatabase.delete("charging_sessions", null, null)
        db.writableDatabase.delete("samples", null, null)

        db.insert(telemetry(40.0), charging = true)
        val started = db.recentChargingSessions().single()
        assertNull(started.endedAtMs)
        assertEquals(40.0, started.startSoc!!, 0.001)

        // Repeated plugged snapshots update telemetry but never duplicate a session.
        db.insert(telemetry(43.0), charging = true)
        assertEquals(1, db.recentChargingSessions().size)
        assertEquals(40.0, db.recentChargingSessions().single().startSoc!!, 0.001)

        db.insert(telemetry(78.0), charging = false)
        val finished = db.recentChargingSessions().single()
        assertNotNull(finished.endedAtMs)
        assertEquals(78.0, finished.endSoc!!, 0.001)
        assertEquals(38.0, finished.socGain!!, 0.001)
        assertNotNull(finished.durationMs)
    }

    @Test fun invalidSocIsStoredAsMissingRatherThanInvented() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        db.writableDatabase.delete("charging_sessions", null, null)
        db.writableDatabase.delete("samples", null, null)

        db.insert(telemetry(-1.0), charging = true)
        db.insert(telemetry(55.0), charging = false)

        val session = db.recentChargingSessions().single()
        assertNull(session.startSoc)
        assertEquals(55.0, session.endSoc!!, 0.001)
        assertNull(session.socGain)
    }

    @Test fun openSessionRemainsPersistedAndClosesOnNextUnplugSnapshot() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        db.writableDatabase.delete("charging_sessions", null, null)
        db.writableDatabase.delete("samples", null, null)

        db.insert(telemetry(62.0), charging = true)
        val sessionId = db.recentChargingSessions().single().id

        // Re-access through the database singleton, as the service does after recreation.
        val restored = SnapshotDb.get(RuntimeEnvironment.getApplication())
        assertEquals(sessionId, restored.recentChargingSessions().single().id)
        assertNull(restored.recentChargingSessions().single().endedAtMs)

        restored.insert(telemetry(81.0), charging = false)
        assertEquals(81.0, restored.recentChargingSessions().single().endSoc!!, 0.001)
    }
}
