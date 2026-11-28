package com.evolute.telemetrypro.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thin client for the documented, unofficial read-only Evolute companion API. */
class ApiClient(private val tokens: TokenStore) {
    private val jsonType = "application/json; charset=utf-8"
    private val base = "https://app.evassist.ru"
    @Volatile
    private var cachedTelemetryImei: String? = null

    data class Telemetry(
        val soc: Double, val odometer: Double, val stockRange: Double?,
        val online: Boolean, val ready: Boolean, val updatedAt: String?,
        val sensors: Map<String, String>
    )
    data class Tokens(val access: String, val refresh: String, val userId: String?, val userToken: String?, val widgetId: String?)

    private data class HttpResult(
        val code: Int,
        val raw: String,
        val headers: Map<String, String>
    )

    // Never expose vehicle IMEI or database id in an on-screen/network error.
    private fun safePath(path: String): String = when {
        path.startsWith("/client-bff-service/telemetry/") -> "/client-bff-service/telemetry/{imei}"
        Regex("^/car-service/car/v2/[a-fA-F0-9]{24}$").matches(path) -> "/car-service/car/v2/{carId}"
        else -> path
    }

    /**
     * Use Android's platform HttpURLConnection independently of OkHttp.
     * Redirects are disabled so a custom access-token header cannot be silently
     * dropped while following a redirect to another host/path.
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
                .mapNotNull { name -> connection.getHeaderField(name)?.takeIf { it.isNotBlank() }?.let { name to it.take(100) } }
                .toMap()
            return HttpResult(code, raw, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true, retry401: Boolean = true, accessOverride: Tokens? = null): JSONObject {
        val usedTokens = if (auth) accessOverride ?: tokens.get() else null
        val access = usedTokens?.access?.trim().orEmpty()
        if (auth && access.isEmpty()) throw IOException("Нет сохранённого access-token: запрос остановлен до отправки")

        val response = execute(path, method, body, if (auth) access else null)
        if (response.code == 401 && auth && retry401) {
            // Refresh tokens are rotated by the API. Multiple ApiClient instances
            // (Activity + foreground Service) must not refresh the same pair twice.
            val refreshed = refresh(usedTokens)
            return request(path, method, body, auth, retry401 = false, accessOverride = refreshed)
        }
        if (response.code !in 200..299) {
            val detail = safeErrorDetail(response.raw)
            val retryNote = if (response.code == 401 && !retry401) " (повтор после refresh-token)" else ""
            val responseMeta = response.headers.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val authMeta = if (auth) "transport=Android-HttpURLConnection; access-token-configured=yes" else "transport=Android-HttpURLConnection"
            val suffix = buildList {
                if (detail != null) add(detail)
                add(authMeta)
                if (responseMeta.isNotBlank()) add(responseMeta)
            }.joinToString("; ").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            throw IOException("Evolute API HTTP ${response.code}: $method ${safePath(path)}$retryNote$suffix")
        }
        return if (response.raw.isBlank()) JSONObject() else JSONObject(response.raw)
    }

    fun requestOtp(phone: String, phoneCountry: String) {
        val info = request("/id-service/info", auth = false)
        if (info.optBoolean("capcha", false)) throw IOException("API требует CAPTCHA; автоматический обход не выполняется")
        request("/id-service/auth/sign-up", "POST", JSONObject().put("phone", phone).put("phoneCountry", phoneCountry).put("capchaToken", ""), false)
    }

    fun signIn(phone: String, code: String) {
        val obj = request("/id-service/auth/sign-in", "POST", JSONObject().put("phone", phone).put("code", code), false)
        tokens.save(Tokens(obj.getString("accessToken"), obj.getString("refreshToken"), obj.optString("userId"), obj.optString("userToken"), obj.optString("widgetId")))
    }

    private fun refresh(rejected: Tokens?): Tokens {
        synchronized(REFRESH_LOCK) {
            val old = tokens.get() ?: throw IOException("Нет сохранённой авторизации")
            // Another ApiClient instance may already have refreshed and persisted a
            // rotated token pair after this request was sent. Do not rotate it again.
            if (rejected != null && (old.access != rejected.access || old.refresh != rejected.refresh)) {
                if (old.access.isBlank() || old.refresh.isBlank()) throw IOException("Сохранённая сессия содержит пустой токен")
                return old
            }

            val obj = try {
                request("/id-service/auth/refresh-token", "POST", JSONObject().put("refreshToken", old.refresh), false)
            } catch (e: IOException) {
                if (e.message?.contains("HTTP 401") == true) {
                    throw IOException("Сессия Evolute истекла или refresh-token уже использован. Войдите в аккаунт заново.", e)
                }
                throw e
            }
            // Reject incomplete refresh responses before they can overwrite a working session.
            val newAccess = obj.optString("accessToken").ifBlank { obj.optString("access_token") }.trim()
            val newRefresh = obj.optString("refreshToken").ifBlank { obj.optString("refresh_token") }.trim()
            if (newAccess.isEmpty() || newRefresh.isEmpty()) {
                throw IOException("Refresh-token ответ не содержит непустую пару accessToken/refreshToken; сессия не перезаписана")
            }
            val updated = Tokens(
                newAccess,
                newRefresh,
                obj.optString("userId").takeIf { it.isNotBlank() && it != "null" } ?: old.userId,
                obj.optString("userToken").takeIf { it.isNotBlank() && it != "null" } ?: old.userToken,
                obj.optString("widgetId").takeIf { it.isNotBlank() && it != "null" } ?: old.widgetId
            )
            tokens.save(updated)
            val stored = tokens.get() ?: throw IOException("Новый токен не удалось прочитать из защищённого хранилища")
            if (stored.access != updated.access || stored.refresh != updated.refresh) {
                throw IOException("Проверка сохранения новой пары токенов не пройдена; повтор запроса отменён")
            }
            return stored
        }
    }

    companion object {
        // Shared by every ApiClient in this app process. Refresh-token rotation means
        // per-instance synchronization is insufficient.
        private val REFRESH_LOCK = Any()
    }

    /**
     * Preflight account endpoints to distinguish a rejected access token
     * from a vehicle permission/context problem at car search.
     * No credentials or vehicle identifiers are exposed in diagnostics.
     */
    private fun checkAccountAccess(): String {
        try {
            request("/id-service/user")
        } catch (e: IOException) {
            throw IOException("Проверка токена не пройдена (/id-service/user): ${e.message}", e)
        }
        try {
            request("/id-service/org/my")
        } catch (e: IOException) {
            throw IOException("Токен принят, но проверка организаций не пройдена (/id-service/org/my): ${e.message}", e)
        }
        return "GET /id-service/user=200; GET /id-service/org/my=200"
    }

    private fun safeErrorDetail(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val parts = listOf("message", "error", "error_description", "errorMessage", "detail", "code").mapNotNull { key ->
                val value = obj.opt(key)
                if (value == null || value == JSONObject.NULL) null
                else value.toString().takeIf { it.isNotBlank() && it != "null" }
                    ?.replace(Regex("[\\r\\n\\t]+"), " ")
                    ?.take(120)
            }
            parts.joinToString("; ").takeIf { it.isNotBlank() }?.take(240)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTelemetryImei(): String {
        cachedTelemetryImei?.let { return it }

        return synchronized(this) {
            cachedTelemetryImei?.let { return@synchronized it }

            val vehicles = request(
                "/car-service/car/v2/search",
                "POST",
                JSONObject()
                    .put("limit", 20)
                    .put("offset", 0)
                    .put("filters", JSONArray())
            )

            val rows = vehicles.optJSONArray("rows")
                ?: throw IOException("API не вернул список автомобилей")

            if (rows.length() == 0) {
                throw IOException("В аккаунте не найден автомобиль")
            }

            val carId = rows.getJSONObject(0).getString("_id")
            val car = request("/car-service/car/v2/$carId")
            val imei = car.optString("imei")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: throw IOException("В карточке автомобиля отсутствует IMEI")

            cachedTelemetryImei = imei
            imei
        }
    }

    fun fetchTelemetry(): Telemetry {
        // Resolve the vehicle once per ApiClient; subsequent polls request telemetry only.
        val imei = resolveTelemetryImei()
        return TelemetryParser.parse(request("/client-bff-service/telemetry/$imei"))
    }
}

/** Android Keystore AES-GCM-backed token storage is implemented separately; no plaintext tokens in preferences/logs. */
interface TokenStore {
    fun get(): ApiClient.Tokens?
    fun save(tokens: ApiClient.Tokens)
    fun clear()
}
