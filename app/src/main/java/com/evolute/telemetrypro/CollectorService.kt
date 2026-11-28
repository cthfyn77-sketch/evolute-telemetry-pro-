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

/** Collects read-only snapshots exclusively through the local evaconnect-mcp bridge. */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Подключение к EVACONNECT MCP"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive == true) return START_STICKY
        pollJob = scope.launch {
            val bridgeToken = try {
                BridgeConfigStore(this@CollectorService).get()
            } catch (e: Exception) {
                CollectionDiagnostics.record(this@CollectorService, e.message ?: "Ключ MCP bridge недоступен", false)
                null
            }
            if (bridgeToken.isNullOrBlank()) {
                CollectionDiagnostics.record(this@CollectorService, "MCP bridge не настроен", false)
                updateNotification("Настройте ключ локального MCP bridge")
                stopSelf(startId)
                return@launch
            }

            val db = SnapshotDb.get(this@CollectorService)
            val estimator = RangeEstimator()
            db.recentSamples().forEach(estimator::add)
            val mcp = EvaconnectMcpClient("http://127.0.0.1:8765", bridgeToken)
            var consecutiveFailures = 0

            while (isActive) {
                var nextDelayMs = 30_000L
                try {
                    val data = mcp.fetchTelemetry()
                    val charging = data.sensors["isChargingGunInserted"]?.let {
                        it.equals("true", true) || it == "1"
                    } == true
                    // A snapshot is counted only after SQLite confirms the insert.
                    db.insert(data, charging)
                    CollectionDiagnostics.record(
                        this@CollectorService,
                        "Снимок MCP записан • SOC ${data.soc.toInt()}% • одометр ${"%.1f".format(data.odometer)} км",
                        true
                    )
                    consecutiveFailures = 0
                    nextDelayMs = CollectorRetryBackoff.delayAfterFailure(0)
                    if (data.online) {
                        estimator.add(RangeEstimator.Sample(data.odometer, data.soc, System.currentTimeMillis(), charging))
                        val range = estimator.estimate(data.soc, data.stockRange)
                        val rangeText = range?.let { " • запас ~${it.rangeKm.toInt()} км" }.orEmpty()
                        updateNotification("MCP • SOC ${data.soc.toInt()}% • одометр ${"%.1f".format(data.odometer)} км$rangeText")
                    } else {
                        updateNotification("MCP: снимок записан • автомобиль офлайн")
                    }
                } catch (e: AuthenticationRequiredException) {
                    CollectionDiagnostics.record(this@CollectorService, "Авторизация MCP bridge: ключ не принят", false)
                    updateNotification("Ключ MCP bridge не принят • проверьте настройки")
                    stopSelf()
                    return@launch
                } catch (e: Exception) {
                    consecutiveFailures = CollectorRetryBackoff.nextFailureCount(consecutiveFailures)
                    nextDelayMs = CollectorRetryBackoff.delayAfterFailure(consecutiveFailures)
                    val reason = e.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(110) ?: "ошибка MCP bridge"
                    CollectionDiagnostics.record(this@CollectorService, reason, false)
                    updateNotification("MCP: нет свежих данных • повтор через ${nextDelayMs / 1000} с • $reason")
                }
                delay(nextDelayMs)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits background dataSync foreground services to six hours per 24 hours.
        pollJob?.cancel()
        stopSelf(startId)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL, "Сбор телеметрии", NotificationManager.IMPORTANCE_LOW
                ))
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else Notification.Builder(this)
        return builder.setContentTitle("Evolute Telemetry Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
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
