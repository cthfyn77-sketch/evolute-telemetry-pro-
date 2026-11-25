package com.evolute.telemetrypro.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Read-only client for the Evolute companion API. */
class ApiClient(private val tokens: TokenStore) {
    data class Tokens(val access: String, val refresh: String, val userId: String?, val userToken: String?, val widgetId: String?)
    data class Telemetry(
        val soc: Double, val odometer: Double, val stockRange: Double?,
        val online: Boolean, val ready: Boolean, val updatedAt: String?,
        val sensors: Map<String, String>
    )

    private data class Response(val code: Int, val body: String)
    private val base = "https://app.evassist.ru"
    @Volatile private var cachedImei: String? = null

    private fun execute(path: String, method: String, body: JSONObject?, access: String?): Response {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("accept", "application/json")
            setRequestProperty("cache-control", "no-cache")
            setRequestProperty("x-device", "android")
            setRequestProperty("x-app", "mobile")
            setRequestProperty("x-app-version", "5.1.22 (740)")
            setRequestProperty("content-type", "application/json")
            if (access != null) setRequestProperty("access-token", access)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return Response(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty())
        } finally { c.disconnect() }
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true, retry: Boolean = true): JSONObject {
        val current = if (auth) tokens.get() ?: throw IOException("Требуется авторизация") else null
        val response = execute(path, method, body, current?.access)
        if (response.code == 401 && auth && retry) {
            refresh(current!!)
            return request(path, method, body, auth, false)
        }
        if (response.code !in 200..299) {
            val detail = try {
                val j = JSONObject(response.body)
                listOf("message", "error_description", "detail", "code")
                    .mapNotNull { j.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
                    .joinToString("; ").take(180)
            } catch (_: Exception) { "" }
            throw IOException("Evolute API HTTP ${response.code}: ${path.substringBeforeLast('/')}${if (detail.isBlank()) "" else " — $detail"}")
        }
        return if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
    }

    fun requestOtp(phone: String, country: String) {
        require(phone.matches(Regex("[0-9]{8,15}"))) { "Проверьте номер телефона: только цифры с кодом страны" }
        require(country.matches(Regex("[A-Za-z]{2}"))) { "Код страны должен состоять из двух букв" }
        if (request("/id-service/info", auth = false).optBoolean("capcha", false))
            throw IOException("API требует CAPTCHA; автоматический обход не выполняется")
        request("/id-service/auth/sign-up", "POST",
            JSONObject().put("phone", phone).put("phoneCountry", country.lowercase()).put("capchaToken", ""), false)
    }

    fun signIn(phone: String, code: String) {
        require(code.matches(Regex("[0-9]{4,8}"))) { "Проверьте SMS-код" }
        val j = request("/id-service/auth/sign-in", "POST", JSONObject().put("phone", phone).put("code", code), false)
        val access = j.optString("accessToken")
        val refresh = j.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) throw IOException("Ответ авторизации не содержит токены")
        tokens.save(Tokens(access, refresh, j.optString("userId"), j.optString("userToken"), j.optString("widgetId")))
    }

    private fun refresh(rejected: Tokens) {
        synchronized(REFRESH_LOCK) {
            val current = tokens.get() ?: throw IOException("Требуется повторная авторизация")
            if (current.access != rejected.access || current.refresh != rejected.refresh) return
            val j = request("/id-service/auth/refresh-token", "POST", JSONObject().put("refreshToken", current.refresh), false)
            val access = j.optString("accessToken").ifBlank { j.optString("access_token") }
            val refresh = j.optString("refreshToken").ifBlank { j.optString("refresh_token") }
            if (access.isBlank() || refresh.isBlank()) throw IOException("Не удалось обновить сессию; повторите вход")
            tokens.save(Tokens(access, refresh,
                j.optString("userId").ifBlank { current.userId ?: "" },
                j.optString("userToken").ifBlank { current.userToken ?: "" },
                j.optString("widgetId").ifBlank { current.widgetId ?: "" }))
        }
    }

    private fun resolveImei(): String {
        cachedImei?.let { return it }
        synchronized(this) {
            cachedImei?.let { return it }
            val list = request("/car-service/car/v2/search", "POST",
                JSONObject().put("limit", 20).put("offset", 0).put("filters", JSONArray()))
            val rows = list.optJSONArray("rows") ?: throw IOException("API не вернул список автомобилей")
            if (rows.length() == 0) throw IOException("В аккаунте не найден автомобиль")
            val carId = rows.getJSONObject(0).optString("_id")
            if (carId.isBlank()) throw IOException("Не найден идентификатор автомобиля")
            val car = request("/car-service/car/v2/$carId")
            val imei = car.optString("imei")
            if (imei.isBlank()) throw IOException("В карточке автомобиля нет IMEI")
            cachedImei = imei
            return imei
        }
    }

    fun fetchTelemetry(): Telemetry {
        val j = request("/client-bff-service/telemetry/${resolveImei()}")
        val sensors = j.optJSONObject("sensors") ?: throw IOException("В ответе нет sensors")
        fun number(key: String): Double? {
            val v = sensors.opt(key) ?: return null
            if (v == JSONObject.NULL || v is Boolean) return null
            return v.toString().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }
        val soc = number("batteryPercentage")?.takeIf { it <= 100.0 } ?: throw IOException("SOC недоступен")
        val odo = number("odometer") ?: throw IOException("Одометр недоступен")
        val names = listOf("batteryTemp", "12VBatteryVoltage", "inBoardTemp", "outsideTemp",
            "remainsBatteryMileage", "isIgnitionOn", "isParkedOn", "isCentralLockingOn",
            "isChargingGunInserted", "signalLevel", "course", "lat", "lon")
        val safe = names.mapNotNull { key ->
            sensors.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { key to it.toString() }
        }.toMap()
        return Telemetry(soc, odo, number("remainsBatteryMileage"),
            j.optBoolean("isOnline"), j.optBoolean("isCarStateReady"),
            j.optString("carStateUpdatedAt").takeIf { it.isNotBlank() && it != "null" }, safe)
    }

    companion object { private val REFRESH_LOCK = Any() }
}

interface TokenStore {
    fun get(): ApiClient.Tokens?
    fun save(tokens: ApiClient.Tokens)
    fun clear()
}
