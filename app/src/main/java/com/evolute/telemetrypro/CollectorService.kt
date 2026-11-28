package com.evolute.telemetrypro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.evolute.telemetrypro.data.ApiClient
import com.evolute.telemetrypro.data.KeystoreTokenStore
import com.evolute.telemetrypro.data.RangeEstimator
import com.evolute.telemetrypro.data.SnapshotDb
import com.evolute.telemetrypro.data.TripRecorder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

/** Polling and token refresh use the exact verified EVACONNECT Python bridge from build 273. */
class CollectorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private val registeredLocationProviders = mutableSetOf<String>()
    private var locationForegroundTypeEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification("Ожидание подключения EVACONNECT"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification("Ожидание подключения EVACONNECT"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            pollJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "ENABLE_GPS") {
            getSharedPreferences(GPS_PREFS, MODE_PRIVATE).edit().putBoolean(GPS_ENABLED, true).apply()
            promoteForegroundForLocation()
        }
        if (intent?.action == "DISABLE_GPS") {
            getSharedPreferences(GPS_PREFS, MODE_PRIVATE).edit().putBoolean(GPS_ENABLED, false).apply()
            stopGpsUpdates()
            demoteForegroundFromLocation()
        }
        if (pollJob?.isActive == true) {
            syncGpsTracking(SnapshotDb.get(this))
            return START_STICKY
        }

        pollJob = scope.launch {
            val tokenStore = KeystoreTokenStore(this@CollectorService)
            val db = SnapshotDb.get(this@CollectorService)
            val estimator = RangeEstimator()
            val tripRecorder = TripRecorder()
            db.recentSamples().forEach(estimator::add)
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                val bridge = Python.getInstance().getModule("evaconnect_android")
                while (isActive) {
                    try {
                        val saved = tokenStore.get() ?: throw IOException("Сначала выполните вход по SMS")
                        val raw = bridge.callAttr("fetch_telemetry", saved.access, saved.refresh).toString()
                        val result = JSONObject(raw)

                        // EVACONNECT rotates refresh tokens; persist the returned pair as build 273 does.
                        val access = result.optString("access_token", saved.access).ifBlank { saved.access }
                        val refresh = result.optString("refresh_token", saved.refresh).ifBlank { saved.refresh }
                        if (access != saved.access || refresh != saved.refresh) {
                            tokenStore.save(saved.copy(access = access, refresh = refresh))
                        }
                        if (!result.optBoolean("ok", false)) {
                            throw IOException(result.optString("error", "EVACONNECT не вернул телеметрию"))
                        }

                        // Keep the complete API sensor object returned by the build 273 bridge.
                        val rawSensors = result.optString("sensors_json", "{}")
                        val fullResponse = JSONObject(rawSensors)
                        val sensorObject = fullResponse.optJSONObject("sensors") ?: fullResponse
                        val sensors = buildMap {
                            val keys = sensorObject.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = sensorObject.opt(key)
                                if (value != null && value != JSONObject.NULL) put(key, value.toString())
                            }
                        }
                        val telemetry = ApiClient.Telemetry(
                            soc = result.getDouble("soc"),
                            odometer = result.getDouble("odometer"),
                            stockRange = if (result.isNull("stock_range")) null else result.getDouble("stock_range"),
                            online = result.optBoolean("online", false),
                            ready = result.optBoolean("ready", false),
                            updatedAt = result.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
                            sensors = sensors
                        )

                        val charging = telemetry.sensors["isChargingGunInserted"]?.let {
                            it.equals("true", true) || it == "1"
                        } == true
                        val now = System.currentTimeMillis()
                        tripRecorder.record(db, telemetry, charging, now)
                        db.insert(telemetry, charging)
                        syncGpsTracking(db)
                        estimator.add(RangeEstimator.Sample(telemetry.odometer, telemetry.soc, now, charging))
                        val range = estimator.estimate(telemetry.soc, telemetry.stockRange)
                        val rangeText = range?.let { " • запас ~${it.rangeKm.toInt()} км" }.orEmpty()
                        val success = "Снимок EVACONNECT записан • SOC ${telemetry.soc.toInt()}% • ${"%.1f".format(telemetry.odometer)} км"
                        updateNotification(success + rangeText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val reason = e.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(160)
                            ?: e.javaClass.simpleName
                        updateNotification("EVACONNECT: $reason")
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


    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun promoteForegroundForLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasLocationPermission() || locationForegroundTypeEnabled) return
        try {
            startForeground(
                NOTIFICATION_ID, notification("GPS-маршрут готов к записи"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            locationForegroundTypeEnabled = true
        } catch (_: SecurityException) {
            locationForegroundTypeEnabled = false
        }
    }

    private fun syncGpsTracking(db: SnapshotDb) {
        val trip = db.activeTrip()
        val hasPermission = hasLocationPermission()
        val gpsOptedIn = getSharedPreferences(GPS_PREFS, MODE_PRIVATE).getBoolean(GPS_ENABLED, false)
        val shouldTrack = trip != null && trip.status == "active" && hasPermission
        if (!shouldTrack) {
            stopGpsUpdates()
            // Keep the location FGS type while the user has explicitly enabled routes.
            // This avoids upgrading a dataSync-only service to location after the app
            // has moved to the background and Android's while-in-use checks may reject it.
            if ((!gpsOptedIn || !hasPermission) && locationForegroundTypeEnabled) {
                demoteForegroundFromLocation()
            }
            return
        }
        promoteForegroundForLocation()
        val manager = locationManager ?: getSystemService(LocationManager::class.java).also { locationManager = it }
        if (locationListener == null) {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val dbNow = SnapshotDb.get(this@CollectorService)
                    val active = dbNow.activeTrip() ?: return
                    dbNow.insertTripRoutePoint(
                        active.id, location.time, location.latitude, location.longitude,
                        location.accuracy.takeIf { location.hasAccuracy() }?.toDouble(),
                        location.speed.takeIf { location.hasSpeed() }?.toDouble()
                    )
                }
            }
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            if (provider in registeredLocationProviders) continue
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 5_000L, 5f, locationListener!!, Looper.getMainLooper())
                    registeredLocationProviders.add(provider)
                }
            } catch (_: SecurityException) {
                // Permission may be revoked while the service is running.
            } catch (_: IllegalArgumentException) {
                // Provider is unavailable on this device.
            }
        }
    }

    private fun demoteForegroundFromLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && locationForegroundTypeEnabled) {
            try {
                startForeground(NOTIFICATION_ID, notification("Сбор телеметрии • GPS выключен"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (_: Exception) { }
            locationForegroundTypeEnabled = false
        }
    }

    private fun stopGpsUpdates() {
        val listener = locationListener
        if (listener != null) {
            try { locationManager?.removeUpdates(listener) } catch (_: SecurityException) { }
        }
        registeredLocationProviders.clear()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ dataSync foreground-service quota; MainActivity restarts collection on return.
        pollJob?.cancel()
        stopSelf(startId)
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
        stopGpsUpdates()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "evolute_telemetry_pro"
        private const val NOTIFICATION_ID = 821
        private const val GPS_PREFS = "gps_route_settings"
        private const val GPS_ENABLED = "enabled"
    }
}
