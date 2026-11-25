package com.evolute.telemetrypro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.evolute.telemetrypro.data.ApiClient
import com.evolute.telemetrypro.data.KeystoreTokenStore
import com.evolute.telemetrypro.data.RangeEstimator
import com.evolute.telemetrypro.data.SnapshotDb
import kotlinx.coroutines.*

class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Подготовка подключения"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive == true) return START_STICKY
        pollJob = scope.launch {
            val db = SnapshotDb.get(this@CollectorService)
            val estimator = RangeEstimator()
            db.recentSamples().forEach(estimator::add)
            val api = ApiClient(KeystoreTokenStore(this@CollectorService))
            while (isActive) {
                try {
                    val data = api.fetchTelemetry()
                    val charging = data.sensors["isChargingGunInserted"]?.let {
                        it.equals("true", true) || it == "1"
                    } == true
                    db.insert(data, charging)
                    estimator.add(RangeEstimator.Sample(data.odometer, data.soc, System.currentTimeMillis(), charging))
                    val range = estimator.estimate(data.soc, data.stockRange)
                    val rangeText = range?.let { " • запас ~${it.rangeKm.toInt()} км" }.orEmpty()
                    updateNotification("SOC ${data.soc.toInt()}% • опрос каждые 30 с$rangeText")
                } catch (e: Exception) {
                    updateNotification("Нет свежих данных • ${e.message?.take(70) ?: "ошибка API"}")
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Сбор телеметрии", NotificationManager.IMPORTANCE_LOW))
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setContentTitle("Evolute Telemetry Pro").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { private const val CHANNEL = "evolute_telemetry_pro"; private const val NOTIFICATION_ID = 821 }
}
