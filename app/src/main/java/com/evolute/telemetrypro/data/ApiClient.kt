package com.evolute.telemetrypro.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A terminal authentication failure requires interactive sign-in. */
class AuthenticationRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Read-only client for the Evolute companion API. */
class ApiClient(private val tokens: TokenStore) {
    data class Tokens(val access: String, val refresh: String, val userId: String?, val userToken: String?, val widgetId: String?)
    data class Telemetry(
        val soc: Double, val odometer: Double, val stockRange: Double?,
        val online: Boolean, val ready: Boolean?, val updatedAt: String?,
        val sensors: Map<String, String>
    )

    private data class HttpResult(
        val code: Int,
        val raw: String,
        val headers: Map<String, String>
    )

    private val base = "https://app.evassist.ru"
    @Volatile private var cachedImei: String? = null

    // Never expose vehicle IMEI or internal car identifiers in UI diagnostics.
    private fun safePath(path: String): String = when {
        path.startsWith("/client-bff-service/telemetry/") -> "/client-bff-service/telemetry/{imei}"
        Regex("^/car-service/car/v2/[a-fA-F0-9]{24}$").matches(path) -> "/car-service/car/v2/{carId}"
        else -> path
    }

    /**
     * Match the transport used by the working Evolute Telemetry Collector.
     * Redirects are disabled so the custom access-token header cannot be silently
     * dropped while following a redirect to another host or path.
     */
    private fun execute(path: String, method: String, body: JSONObject?, access: String?): HttpResult {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
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
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val raw = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val headers = listOf("www-authenticate", "x-request-id", "x-correlation-id")
                .mapNotNull { name ->
                    connection.getHeaderField(name)?.takeIf { it.isNotBlank() }?.let { name to it.take(100) }
                }.toMap()
            return HttpResult(code, raw, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = true,
        retry401: Boolean = true,
        accessOverride: Tokens? = null
    ): JSONObject {
        val usedTokens = if (auth) accessOverride ?: tokens.get() else null
        val access = usedTokens?.access?.trim().orEmpty()
        if (auth && access.isEmpty()) throw AuthenticationRequiredException("Нет сохранённого access-token")

        val response = execute(path, method, body, if (auth) access else null)
        if (response.code == 401 && auth && retry401) {
            val refreshed = refresh(usedTokens)
            return request(path, method, body, auth, retry401 = false, accessOverride = refreshed)
        }
        if (response.code == 401 && auth) {
            // Preserve only safe server diagnostics: never include request headers,
            // access/refresh tokens, IMEI, or raw response payload.
            val detail = safeErrorDetail(response.raw)
            val meta = response.headers.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val suffix = buildList {
                add("HTTP 401 после refresh")
                if (detail != null) add(detail)
                if (meta.isNotBlank()) add(meta)
            }.joinToString("; ")
            throw AuthenticationRequiredException("Сервер отклонил access-token даже после обновления сессии: $suffix")
        }
        if (response.code !in 200..299) {
            val detail = safeErrorDetail(response.raw)
            val responseMeta = response.headers.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val suffix = buildList {
                if (detail != null) add(detail)
                add(if (auth) "transport=Android-HttpURLConnection; access-token-configured=yes"
                    else "transport=Android-HttpURLConnection")
                if (responseMeta.isNotBlank()) add(responseMeta)
            }.joinToString("; ").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            throw IOException("Evolute API HTTP ${response.code}: $method ${safePath(path)}$suffix")
        }
        return if (response.raw.isBlank()) JSONObject() else try {
            JSONObject(response.raw)
        } catch (e: Exception) {
            throw IOException("Некорректный JSON от Evolute API: $method ${safePath(path)}", e)
        }
    }

    fun requestOtp(phone: String, country: String) {
        require(phone.matches(Regex("[0-9]{8,15}"))) { "Проверьте номер телефона: только цифры с кодом страны" }
        require(country.matches(Regex("[A-Za-z]{2}"))) { "Код страны должен состоять из двух букв" }
        if (request("/id-service/info", auth = false).optBoolean("capcha", false)) {
            throw IOException("API требует CAPTCHA; автоматический обход не выполняется")
        }
        request(
            "/id-service/auth/sign-up", "POST",
            JSONObject().put("phone", phone).put("phoneCountry", country.lowercase()).put("capchaToken", ""),
            auth = false
        )
    }

    fun signIn(phone: String, code: String) {
        require(code.matches(Regex("[0-9]{4,8}"))) { "Проверьте SMS-код" }
        val json = request(
            "/id-service/auth/sign-in", "POST",
            JSONObject().put("phone", phone).put("code", code),
            auth = false
        )
        val access = json.optString("accessToken").trim()
        val refresh = json.optString("refreshToken").trim()
        if (access.isEmpty() || refresh.isEmpty()) throw IOException("Ответ авторизации не содержит токены")
        tokens.save(Tokens(
            access, refresh,
            json.optString("userId").takeIf { it.isNotBlank() && it != "null" },
            json.optString("userToken").takeIf { it.isNotBlank() && it != "null" },
            json.optString("widgetId").takeIf { it.isNotBlank() && it != "null" }
        ))
    }

    /**
     * Refresh tokens are single-use/rotating. The lock is shared by every ApiClient
     * instance in this process, and the stored pair is re-read before rotation.
     */
    private fun refresh(rejected: Tokens?): Tokens {
        synchronized(REFRESH_LOCK) {
            val current = tokens.get() ?: throw AuthenticationRequiredException("Требуется повторная авторизация")
            if (rejected != null && (current.access != rejected.access || current.refresh != rejected.refresh)) {
                if (current.access.isBlank() || current.refresh.isBlank()) {
                    throw AuthenticationRequiredException("Сохранённая сессия содержит пустые токены")
                }
                return current
            }

            val response = execute(
                "/id-service/auth/refresh-token", "POST",
                JSONObject().put("refreshToken", current.refresh), null
            )
            if (response.code in TERMINAL_REFRESH_CODES) {
                tokens.clear()
                throw AuthenticationRequiredException("Сессия истекла или отозвана; войдите в аккаунт заново")
            }
            if (response.code !in 200..299) {
                throw IOException("Не удалось обновить сессию Evolute: HTTP ${response.code}")
            }

            val json = try {
                JSONObject(response.raw)
            } catch (e: Exception) {
                throw IOException("Некорректный JSON при обновлении сессии Evolute", e)
            }
            val access = json.optString("accessToken").ifBlank { json.optString("access_token") }.trim()
            val refresh = json.optString("refreshToken").ifBlank { json.optString("refresh_token") }.trim()
            if (access.isEmpty() || refresh.isEmpty()) {
                throw IOException("Ответ обновления сессии не содержит новую пару токенов; старая сессия сохранена")
            }

            val updated = Tokens(
                access, refresh,
                json.optString("userId").takeIf { it.isNotBlank() && it != "null" } ?: current.userId,
                json.optString("userToken").takeIf { it.isNotBlank() && it != "null" } ?: current.userToken,
                json.optString("widgetId").takeIf { it.isNotBlank() && it != "null" } ?: current.widgetId
            )
            tokens.save(updated)
            val stored = tokens.get() ?: throw IOException("Новая сессия не прочитана из Android Keystore")
            if (stored.access != updated.access || stored.refresh != updated.refresh) {
                throw IOException("Проверка сохранения новой пары токенов не пройдена")
            }
            return stored
        }
    }

    private fun safeErrorDetail(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val json = JSONObject(raw)
            listOf("message", "error", "error_description", "errorMessage", "detail", "code")
                .mapNotNull { key ->
                    val value = json.opt(key)
                    if (value == null || value == JSONObject.NULL) null
                    else value.toString().takeIf { it.isNotBlank() && it != "null" }
                        ?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(120)
                }.joinToString("; ").takeIf { it.isNotBlank() }?.take(240)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveImei(): String {
        cachedImei?.let { return it }
        return synchronized(this) {
            cachedImei?.let { return@synchronized it }
            val vehicles = try {
                request(
                    "/car-service/car/v2/search", "POST",
                    JSONObject().put("limit", 20).put("offset", 0).put("filters", JSONArray())
                )
            } catch (e: Exception) {
                throw IOException("Этап поиска автомобиля: ${e.message ?: "ошибка API"}", e)
            }
            val rows = vehicles.optJSONArray("rows")
                ?: throw IOException("Этап поиска автомобиля: API не вернул массив rows")
            if (rows.length() == 0) throw IOException("Этап поиска автомобиля: в аккаунте нет доступного автомобиля")

            val carId = rows.getJSONObject(0).optString("_id").trim()
            if (carId.isEmpty() || carId == "null") {
                throw IOException("Этап поиска автомобиля: в первой записи отсутствует _id")
            }
            val car = try {
                request("/car-service/car/v2/$carId")
            } catch (e: Exception) {
                throw IOException("Этап чтения карточки автомобиля: ${e.message ?: "ошибка API"}", e)
            }
            val imei = car.optString("imei").trim()
            if (imei.isEmpty() || imei == "null") {
                throw IOException("Этап чтения карточки автомобиля: API не вернул IMEI")
            }
            cachedImei = imei
            imei
        }
    }

    fun fetchTelemetry(): Telemetry {
        val imei = resolveImei()
        val json = try {
            request("/client-bff-service/telemetry/$imei")
        } catch (e: Exception) {
            throw IOException("Этап получения телеметрии: ${e.message ?: "ошибка API"}", e)
        }
        val sensors = json.optJSONObject("sensors")
            ?: throw IOException("Этап разбора телеметрии: в ответе API отсутствует объект sensors")

        fun number(key: String): Double? {
            val raw = sensors.opt(key) ?: return null
            if (raw == JSONObject.NULL || raw is Boolean) return null
            val value = raw.toString().toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() && it >= 0.0 }
        }

        val soc = number("batteryPercentage")?.takeIf { it <= 100.0 }
            ?: throw IOException("Этап разбора телеметрии: SOC отсутствует или вне диапазона 0–100")
        val odometer = number("odometer")
            ?: throw IOException("Этап разбора телеметрии: отсутствует одометр")

        // Preserve the same confirmed vehicle-state sensor whitelist as the Collector.
        val sensorNames = listOf(
            "batteryTemp", "12VBatteryVoltage", "inBoardTemp", "outsideTemp",
            "remainsBatteryMileage", "isIgnitionOn", "isParkedOn", "isCentralLockingOn",
            "isChargingGunInserted", "signalLevel"
        )
        val safeSensors = sensorNames.mapNotNull { key ->
            sensors.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { key to it.toString() }
        }.toMap()

        return Telemetry(
            soc = soc,
            odometer = odometer,
            stockRange = number("remainsBatteryMileage"),
            online = json.optBoolean("isOnline"),
            ready = if (!json.has("isCarStateReady") || json.isNull("isCarStateReady")) null
                else json.optBoolean("isCarStateReady"),
            updatedAt = json.optString("carStateUpdatedAt").takeIf { it.isNotBlank() && it != "null" },
            sensors = safeSensors
        )
    }

    companion object {
        private val REFRESH_LOCK = Any()
        internal val TERMINAL_REFRESH_CODES = setOf(400, 401, 403)
    }
}

/** Android Keystore AES-GCM-backed token storage is implemented separately. */
interface TokenStore {
    fun get(): ApiClient.Tokens?
    fun save(tokens: ApiClient.Tokens)
    fun clear()
}
