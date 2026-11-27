package com.evolute.telemetrypro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.evolute.telemetrypro.data.AuthenticationRequiredException
import com.evolute.telemetrypro.data.BridgeConfigStore
import com.evolute.telemetrypro.data.EvaconnectMcpClient
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
            val hasSession = runCatching {
                BridgeConfigStore(this@CollectorService).get() != null
            }.getOrDefault(false)
            if (!hasSession) {
                updateNotification("Настройте ключ локального MCP bridge")
                stopSelf(startId)
                return@launch
            }
            val db = SnapshotDb.get(this@CollectorService)
            val estimator = RangeEstimator()
            db.recentSamples().forEach(estimator::add)
            val bridgeToken = BridgeConfigStore(this@CollectorService).get() ?: throw AuthenticationRequiredException("Ключ MCP bridge не настроен")
            val api = EvaconnectMcpClient("http://127.0.0.1:8765", bridgeToken)
            var consecutiveFailures = 0
            while (isActive) {
                var nextDelayMs = 30_000L
                try {
                    val data = api.fetchTelemetry()
                    val charging = data.sensors["isChargingGunInserted"]?.let {
                        it.equals("true", true) || it == "1"
                    } == true
                    db.insert(data, charging)
                    consecutiveFailures = 0
                    nextDelayMs = CollectorRetryBackoff.delayAfterFailure(0)
                    if (data.online) {
                        estimator.add(RangeEstimator.Sample(data.odometer, data.soc, System.currentTimeMillis(), charging))
                        val range = estimator.estimate(data.soc, data.stockRange)
                        val rangeText = range?.let { " • запас ~${it.rangeKm.toInt()} км" }.orEmpty()
                        updateNotification("SOC ${data.soc.toInt()}% • опрос каждые 30 с$rangeText")
                    } else {
                        updateNotification("Автомобиль офлайн • снимок сохранён; расчёт запаса приостановлен")
                    }
                } catch (e: Exception) {
                    if (e is AuthenticationRequiredException) {
                        updateNotification("Ключ MCP bridge не принят • проверьте настройки")
                        stopSelf()
                        return@launch
                    }
                    consecutiveFailures = CollectorRetryBackoff.nextFailureCount(consecutiveFailures)
                    nextDelayMs = CollectorRetryBackoff.delayAfterFailure(consecutiveFailures)
                    updateNotification("Нет свежих данных • повтор через ${nextDelayMs / 1000} с • ${e.message?.take(45) ?: "ошибка MCP bridge"}")
                }
                delay(nextDelayMs)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits background dataSync foreground services to
        // six hours per 24-hour period. Stop cleanly; MainActivity restarts
        // collection when the user returns to the app.
        pollJob?.cancel()
        stopSelf(startId)
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
