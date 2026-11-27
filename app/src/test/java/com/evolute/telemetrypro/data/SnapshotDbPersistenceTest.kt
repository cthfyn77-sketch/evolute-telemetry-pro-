package com.evolute.telemetrypro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SnapshotDbPersistenceTest {
    @Test
    fun telemetryHistoryAndRangeEstimatorInputsSurviveDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("evolute_telemetry_pro.sqlite")

        val db = SnapshotDb.get(context)
        try {
            (0..8).forEach { km ->
                db.insert(
                    ApiClient.Telemetry(
                        soc = 100.0 - km * 0.5,
                        odometer = km.toDouble(),
                        stockRange = 180.0,
                        online = true,
                        ready = false,
                        updatedAt = "2026-11-27T12:00:00Z",
                        sensors = mapOf("12VBatteryVoltage" to (12.0 + km * 0.1).toString())
                    )
                )
                // SnapshotDb timestamps rows with the wall clock. Keep each
                // synthetic sample distinct so RangeEstimator can replay them
                // in strictly increasing capture-time order.
                Thread.sleep(2)
            }

            assertEquals(9L, db.count())
            db.close()

            // SQLiteOpenHelper reopens the same on-disk database, as it does
            // when the application process is recreated.
            val reopened = SnapshotDb.get(context)
            assertEquals(9L, reopened.count())

            val latest = reopened.latest()
            assertNotNull(latest)
            assertEquals(96.0, latest!!.soc, 0.001)
            assertEquals(8.0, latest.odometer, 0.001)
            assertEquals("12.8", latest.sensors["12VBatteryVoltage"])

            val history = reopened.batteryHistory()
            assertEquals(9, history.size)
            assertEquals(0.0, history.first().odometer, 0.001)
            assertEquals(8.0, history.last().odometer, 0.001)
            assertEquals(12.0, org.json.JSONObject(history.first().sensorsJson)
                .getDouble("12VBatteryVoltage"), 0.001)

            val estimator = RangeEstimator()
            reopened.recentSamples().forEach(estimator::add)
            val estimate = estimator.estimate(96.0, latest.stockRange)
            assertNotNull(estimate)
            assertEquals(RangeEstimator.Source.MEASURED_1_KM, estimate!!.source)
            assertEquals(192.0, estimate.rangeKm, 0.001)
        } finally {
            db.close()
            context.deleteDatabase("evolute_telemetry_pro.sqlite")
        }
    }
}
