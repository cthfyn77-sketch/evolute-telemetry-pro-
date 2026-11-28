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

    private fun telemetry(
        soc: Double,
        plugged: Boolean,
        charging: String? = null,
        temp: String = "24.5"
    ) = ApiClient.Telemetry(
        soc, 100.0, null, true, false, null,
        buildMap {
            put("isChargingGunInserted", plugged.toString())
            put("batteryTemp", temp)
            if (charging != null) put("isCharging", charging)
        }
    )

    private fun clean(db: SnapshotDb) {
        db.writableDatabase.delete("charging_intervals", null, null)
        db.writableDatabase.delete("charging_sessions", null, null)
        db.writableDatabase.delete("samples", null, null)
    }

    @Test fun cableOnlyConnectionIsSavedAsZeroGainSession() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        clean(db)
        db.insert(telemetry(40.0, true, "false"), charging = true)
        db.insert(telemetry(40.0, true, "false"), charging = true)
        db.insert(telemetry(40.0, false, "false"), charging = false)

        val session = db.recentChargingSessions().single()
        assertNotNull(session.endedAtMs)
        assertNull(session.energyStartedAtMs)
        assertEquals(0.0, session.socGain!!, 0.001)
        assertEquals(0L, session.energyDurationMs)
        assertNull(session.batteryTempBefore)
        assertNull(session.batteryTempAfter)
    }

    @Test fun transferPauseAndResumeStayInOneCableSession() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        clean(db)
        db.insert(telemetry(40.0, true, "false", "23.0"), charging = true)
        db.insert(telemetry(40.0, true, "true", "23.5"), charging = true)
        db.insert(telemetry(55.0, true, "false", "26.0"), charging = true)
        db.insert(telemetry(55.0, true, "true", "26.2"), charging = true)
        db.insert(telemetry(80.0, true, "false", "31.0"), charging = true)

        val during = db.recentChargingSessions().single()
        assertNull(during.endedAtMs)
        assertNotNull(during.energyStartedAtMs)
        assertNotNull(during.energyStoppedAtMs)
        assertEquals(40.0, during.socBeforeEnergy!!, 0.001)
        assertEquals(80.0, during.socAfterEnergy!!, 0.001)
        assertEquals(23.0, during.batteryTempBefore!!, 0.001)
        assertEquals(31.0, during.batteryTempAfter!!, 0.001)

        db.insert(telemetry(80.0, false, "false", "30.0"), charging = false)
        val finished = db.recentChargingSessions().single()
        assertNotNull(finished.cableRemovedAtMs)
        assertEquals(40.0, finished.socGain!!, 0.001)
        assertTrue(finished.energyDurationMs >= 0L)
    }

    @Test fun openCableSessionSurvivesAdditionalSnapshotsAndUnplug() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        clean(db)
        db.insert(telemetry(62.0, true, "true"), charging = true)
        val id = db.recentChargingSessions().single().id
        assertEquals(id, SnapshotDb.get(RuntimeEnvironment.getApplication()).recentChargingSessions().single().id)
        db.insert(telemetry(81.0, false, "false"), charging = false)
        val finished = db.recentChargingSessions().single()
        assertEquals(id, finished.id)
        assertEquals(19.0, finished.socGain!!, 0.001)
    }

    @Test fun unknownEnergySensorDoesNotPretendCableMeansCharging() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        clean(db)
        val unknown = ApiClient.Telemetry(45.0, 100.0, null, true, false, null,
            mapOf("isChargingGunInserted" to "true", "batteryTemp" to "25.0"))
        db.insert(unknown, charging = true)
        db.insert(unknown.copy(soc = 45.0, sensors = mapOf("isChargingGunInserted" to "false")), charging = false)
        val session = db.recentChargingSessions().single()
        assertNull(session.energyStartedAtMs)
        assertEquals(0.0, session.socGain!!, 0.001)
        assertFalse(session.hasEnergyStateSensor)
    }

    @Test fun invalidSocRemainsMissing() {
        val db = SnapshotDb.get(RuntimeEnvironment.getApplication())
        clean(db)
        db.insert(telemetry(-1.0, true, "true"), charging = true)
        db.insert(telemetry(55.0, false, "false"), charging = false)
        val session = db.recentChargingSessions().single()
        assertNull(session.socBeforeEnergy)
        assertEquals(55.0, session.socAfterEnergy!!, 0.001)
        assertNull(session.socGain)
    }
}
