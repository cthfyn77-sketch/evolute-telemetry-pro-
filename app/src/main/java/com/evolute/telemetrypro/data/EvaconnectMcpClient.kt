package com.evolute.telemetrypro.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only Android client for the local EVACONNECT MCP bridge.
 * The bridge must be running in Termux on the same device.
 */
class EvaconnectMcpClient(
    private val baseUrl: String,
    private val bridgeToken: String
) {
    fun fetchTelemetry(): TelemetrySnapshot {
        require(baseUrl == "http://127.0.0.1:8765") {
            "MCP bridge разрешён только на локальном адресе 127.0.0.1:8765"
        }
        if (bridgeToken.length < 32) throw IOException("Не настроен ключ локального MCP bridge")
        val connection = (URL("$baseUrl/v1/status").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("X-Bridge-Token", bridgeToken)
        }
        val body: String
        val code: Int
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        } finally {
            connection.disconnect()
        }
        if (code == 401) throw AuthenticationRequiredException("Ключ MCP bridge не принят; проверьте настройки подключения")
        if (code !in 200..299) throw IOException("Локальный MCP bridge недоступен: HTTP $code")
        val root = JSONObject(body)
        val charge = root.optJSONObject("charge") ?: throw IOException("MCP: отсутствует раздел charge")
        val drive = root.optJSONObject("drive") ?: throw IOException("MCP: отсутствует раздел drive")
        val soc = charge.optDouble("battery_percentage", Double.NaN)
            .takeIf { it.isFinite() && it in 0.0..100.0 } ?: throw IOException("MCP: SOC недоступен")
        val odo = drive.optDouble("odometer", Double.NaN)
            .takeIf { it.isFinite() && it >= 0.0 } ?: throw IOException("MCP: одометр недоступен")
        val online = root.optBoolean("online", false)
        val stockRange = charge.optDouble("range", Double.NaN)
            .takeIf { it.isFinite() && it >= 0.0 }
        val sensors = linkedMapOf<String, String>()
        fun add(section: JSONObject?, source: String, target: String = source) {
            val value = section?.opt(source) ?: return
            if (value != JSONObject.NULL) sensors[target] = value.toString()
        }
        add(charge, "battery_temp", "batteryTemp")
        add(charge, "voltage_12v", "12VBatteryVoltage")
        add(charge, "range", "remainsBatteryMileage")
        add(charge, "charging_gun", "isChargingGunInserted")
        add(drive, "ignition", "isIgnitionOn")
        add(drive, "parked", "isParkedOn")
        add(drive, "signal", "signalLevel")
        val climate = root.optJSONObject("climate")
        add(climate, "inside", "inBoardTemp")
        add(climate, "outside", "outsideTemp")
        add(climate, "target", "climateTargetTemp")
        add(climate, "fan", "climateFanSpeed")
        val body = root.optJSONObject("body")
        add(body, "locked", "isCentralLockingOn")
        add(body, "door_fl", "doorFLStatus")
        add(body, "door_fr", "doorFRStatus")
        add(body, "door_rl", "doorRLStatus")
        add(body, "door_rr", "doorRRStatus")
        add(body, "trunk", "trunkStatus")
        val updatedAt = root.optString("car_state_updated_at").takeIf { it.isNotBlank() && it != "null" }
        // The MCP public_status tool does not expose isCarStateReady. Never infer readiness.
        return TelemetrySnapshot(
            soc = soc,
            odometer = odo,
            stockRange = stockRange,
            online = online,
            ready = null,
            updatedAt = updatedAt,
            sensors = sensors
        )
    }
}
