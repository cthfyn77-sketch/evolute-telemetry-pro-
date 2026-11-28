package com.evolute.telemetrypro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.evolute.telemetrypro.data.KeystoreTokenStore
import com.evolute.telemetrypro.data.SnapshotDb
import com.evolute.telemetrypro.data.TelemetrySnapshot
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

/** Vehicle connection and polling follow the verified build-152 EVACONNECT flow. */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Ожидание подключения EVACONNECT"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            pollJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollJob?.isActive == true) return START_STICKY

        pollJob = scope.launch {
            val tokenStore = KeystoreTokenStore(this@CollectorService)
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val bridge = Python.getInstance().getModule("evaconnect_bridge")
                while (isActive) {
                    try {
                        val saved = tokenStore.get() ?: throw IOException("Сначала выполните вход по SMS")
                        val raw = bridge.callAttr("fetch_telemetry", saved.access, saved.refresh).toString()
                        val result = JSONObject(raw)
                        val access = result.optString("access_token", saved.access).ifBlank { saved.access }
                        val refresh = result.optString("refresh_token", saved.refresh).ifBlank { saved.refresh }
                        if (access != saved.access || refresh != saved.refresh) {
                            tokenStore.save(saved.copy(access = access, refresh = refresh))
                        }
                        if (!result.optBoolean("ok", false)) {
                            throw IOException(result.optString("error", "EVACONNECT не вернул телеметрию"))
                        }

                        val rawSensors = result.optString("sensors_json", "{}")
                        val fullResponse = JSONObject(rawSensors)
                        val sensorObject = fullResponse.optJSONObject("sensors") ?: fullResponse
                        val sensors = buildMap {
                            val keys = sensorObject.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = sensorObject.opt(key)
                                put(key, if (value is JSONObject || value is org.json.JSONArray) value.toString() else value?.toString().orEmpty())
                            }
                        }
                        val snapshot = TelemetrySnapshot(
                            soc = result.getDouble("soc"),
                            odometer = result.getDouble("odometer"),
                            stockRange = if (result.isNull("stock_range")) null else result.getDouble("stock_range"),
                            online = result.optBoolean("online", false),
                            ready = if (result.isNull("ready")) null else result.optBoolean("ready"),
                            updatedAt = result.optString("updated_at").takeIf { it.isNotBlank() },
                            sensors = sensors,
                            rawSensorsJson = rawSensors
                        )
                        SnapshotDb.get(this@CollectorService).insert(snapshot)
                        updateNotification("EVACONNECT: снимок обновлён • SOC ${snapshot.soc.toInt()}% • ${"%.1f".format(snapshot.odometer)} км")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateNotification("EVACONNECT: ${e.message?.take(110) ?: e.javaClass.simpleName}")
                    }
                    delay(30_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateNotification("Не удалось запустить EVACONNECT: ${e.message?.take(110) ?: e.javaClass.simpleName}")
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Сбор телеметрии", NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("DEPRECATION")
    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setContentTitle("Evolute Telemetry Pro").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSharedPreferences("collector_status", MODE_PRIVATE).edit()
            .putString("last_status", text).putLong("updated_at", System.currentTimeMillis()).apply()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "evolute_telemetry_pro"
        private const val NOTIFICATION_ID = 821
    }
}
