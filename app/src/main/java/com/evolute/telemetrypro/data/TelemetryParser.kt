package com.evolute.telemetrypro.data

import org.json.JSONObject
import java.io.IOException

/** Validates and converts one raw read-only telemetry API response. */
internal object TelemetryParser {
    fun parse(tel: JSONObject): ApiClient.Telemetry {
        val sensors = tel.optJSONObject("sensors")
            ?: throw IOException("В ответе нет sensors")

        fun num(key: String): Double? {
            val raw = sensors.opt(key) ?: return null
            if (raw == JSONObject.NULL || raw is Boolean) return null
            val value = raw.toString().toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() && it >= 0.0 }
        }

        val soc = num("batteryPercentage")
            ?.takeIf { it <= 100.0 }
            ?: throw IOException("SOC недоступен или вне диапазона 0–100")
        val odometer = num("odometer")
            ?: throw IOException("Одометр недоступен")
        val updatedAt = tel.opt("carStateUpdatedAt")
            ?.takeUnless { it == JSONObject.NULL }
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }

        return ApiClient.Telemetry(
            soc = soc,
            odometer = odometer,
            stockRange = num("remainsBatteryMileage"),
            online = tel.optBoolean("isOnline"),
            ready = tel.optBoolean("isCarStateReady"),
            updatedAt = updatedAt,
            sensors = listOf(
                "batteryTemp", "12VBatteryVoltage", "inBoardTemp", "outsideTemp",
                "remainsBatteryMileage", "isIgnitionOn", "isParkedOn", "isCentralLockingOn",
                "isChargingGunInserted", "signalLevel", "course", "lat", "lon"
            ).mapNotNull { key ->
                sensors.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { key to it.toString() }
            }.toMap()
        )
    }
}
