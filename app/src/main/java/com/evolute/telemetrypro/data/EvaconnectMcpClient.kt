package com.evolute.telemetrypro.data

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Native Kotlin port of the evaconnect 0.1.0 client + its six read-only MCP tools.
 *
 * The MCP stdio process is intentionally not embedded in Android. Its tested
 * operations and response shaping are exposed as typed in-process methods.
 * Authentication and telemetry protocol behavior mirror the Python client.
 */
class EvaconnectMcpClient(private val tokens: TokenStore) {
    data class Tokens(
        val access: String,
        val refresh: String,
        val userId: String? = null,
        val userToken: String? = null,
        val widgetId: String? = null,
        val carId: String? = null
    )

    data class Telemetry(
        val soc: Double,
        val odometer: Double,
        val stockRange: Double?,
        val online: Boolean,
        val ready: Boolean?,
        val updatedAt: String?,
        val sensors: Map<String, String>,
        val onlineState: Any? = null,
        val warnings: Any? = null,
        val buttons: List<JSONObject> = emptyList()
    )

    data class Vehicle(
        val id: String,
        val vin: String?,
        val imei: String?,
        val plate: String?,
        val brand: String?,
        val model: String?
    )

    data class AuthStatus(
        val hasAccessToken: Boolean,
        val hasRefreshToken: Boolean,
        val hasDefaultCarId: Boolean,
        val sessionValid: Boolean,
        val userId: String? = null
    )

    private data class HttpResult(val code: Int, val body: String)

    private val base = "https://app.evassist.ru"
    @Volatile private var cachedCarId: String? = null
    @Volatile private var cachedImei: String? = null
    @Volatile private var cachedTelemetry: Pair<Long, Telemetry>? = null
    @Volatile private var refreshing = false

    private fun execute(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        access: String? = null
    ): HttpResult {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
            setRequestProperty("cache-control", "no-cache")
            setRequestProperty("x-device", "android")
            setRequestProperty("x-app", "mobile")
            setRequestProperty("x-app-version", "5.1.22 (740)")
            if (!access.isNullOrBlank()) setRequestProperty("access-token", access)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            return HttpResult(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseObject(body: String): JSONObject {
        if (body.isBlank() || body.trim() == "null") return JSONObject()
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("Некорректный JSON в ответе EVACONNECT", e)
        }
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        auth: Boolean = true,
        retry401: Boolean = true
    ): JSONObject {
        val current = if (auth) tokens.get() ?: throw IOException("Требуется авторизация") else null
        val response = execute(path, method, body, current?.access)
        if (response.code == 401 && auth && retry401) {
            refresh(current!!)
            return request(path, method, body, auth = true, retry401 = false)
        }
        if (response.code !in 200..299) {
            throw IOException("$method $path завершился с HTTP ${response.code}")
        }
        return parseObject(response.body)
    }

    // --- Authentication: same endpoints and payloads as evaconnect 0.1.0 ---

    fun getInfo(): JSONObject = request("/id-service/info", auth = false)

    fun requestOtp(phone: String, phoneCountry: String) {
        require(phone.isNotBlank()) { "Телефон обязателен" }
        require(phoneCountry.isNotBlank()) { "phoneCountry обязателен" }
        val info = getInfo()
        if (info.optBoolean("capcha", false)) {
            throw IOException("API требует CAPTCHA; обход CAPTCHA не выполняется")
        }
        request(
            "/id-service/auth/sign-up",
            "POST",
            JSONObject()
                .put("phone", phone)
                .put("phoneCountry", phoneCountry)
                .put("capchaToken", ""),
            auth = false
        )
    }

    fun signIn(phone: String, code: String) {
        require(phone.isNotBlank()) { "Телефон обязателен" }
        require(code.isNotBlank()) { "SMS-код обязателен" }
        val response = request(
            "/id-service/auth/sign-in",
            "POST",
            JSONObject().put("phone", phone).put("code", code),
            auth = false
        )
        val access = response.optString("accessToken")
        val refresh = response.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) {
            throw IOException("Ответ авторизации не содержит пару токенов")
        }
        tokens.save(
            Tokens(
                access = access,
                refresh = refresh,
                userId = response.optString("userId").takeIf { it.isNotBlank() },
                userToken = response.optString("userToken").takeIf { it.isNotBlank() },
                widgetId = response.optString("widgetId").takeIf { it.isNotBlank() }
            )
        )
        cachedCarId = null
        cachedImei = null
        cachedTelemetry = null
    }

    @Synchronized
    fun refresh() {
        val current = tokens.get() ?: throw AuthenticationRequiredException("Требуется повторный вход")
        refresh(current)
    }

    private fun refresh(rejected: Tokens) {
        synchronized(REFRESH_LOCK) {
            val current = tokens.get() ?: throw AuthenticationRequiredException("Требуется повторный вход")
            // Another request may already have rotated the token pair.
            if (current.access != rejected.access || current.refresh != rejected.refresh) return
            if (refreshing) throw IOException("Обновление сессии уже выполняется")
            refreshing = true
            try {
                val response = execute(
                    "/id-service/auth/refresh-token",
                    "POST",
                    JSONObject().put("refreshToken", current.refresh)
                )
                if (response.code in TERMINAL_REFRESH_CODES) {
                    tokens.clear()
                    cachedCarId = null
                    cachedImei = null
                    cachedTelemetry = null
                    throw AuthenticationRequiredException(
                        "Сессия истекла или отозвана; войдите в аккаунт заново"
                    )
                }
                if (response.code !in 200..299) {
                    throw IOException("Не удалось обновить сессию: HTTP ${response.code}")
                }
                val json = parseObject(response.body)
                val access = json.optString("accessToken").ifBlank { json.optString("access_token") }
                val refresh = json.optString("refreshToken").ifBlank { json.optString("refresh_token") }
                if (access.isBlank() || refresh.isBlank()) {
                    throw IOException("Ответ обновления не содержит пару токенов")
                }
                tokens.save(
                    Tokens(
                        access = access,
                        refresh = refresh,
                        userId = json.optString("userId").takeIf { it.isNotBlank() } ?: current.userId,
                        userToken = json.optString("userToken").takeIf { it.isNotBlank() } ?: current.userToken,
                        widgetId = json.optString("widgetId").takeIf { it.isNotBlank() } ?: current.widgetId,
                        carId = current.carId
                    )
                )
            } finally {
                refreshing = false
            }
        }
    }

    fun me(): JSONObject = request("/id-service/user")

    // --- Vehicles and telemetry ---

    fun listVehicles(limit: Int = 20, offset: Int = 0): List<Vehicle> {
        require(limit in 1..100 && offset >= 0)
        val json = request(
            "/car-service/car/v2/search",
            "POST",
            JSONObject()
                .put("limit", limit)
                .put("offset", offset)
                .put("filters", JSONArray())
        )
        val rows = json.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::parseVehicle)
        }
    }

    fun getVehicle(carId: String): Vehicle {
        require(carId.isNotBlank())
        return parseVehicle(request("/car-service/car/v2/${pathSegment(carId)}"))
    }

    fun defaultCarId(): String {
        cachedCarId?.let { return it }
        synchronized(this) {
            cachedCarId?.let { return it }
            val saved = tokens.get()
            saved?.carId?.takeIf { it.isNotBlank() }?.let {
                cachedCarId = it
                return it
            }
            val first = listVehicles().firstOrNull()
                ?: throw IOException("В аккаунте не найден автомобиль")
            cachedCarId = first.id
            if (saved != null) tokens.save(saved.copy(carId = first.id))
            return first.id
        }
    }

    private fun parseVehicle(json: JSONObject): Vehicle {
        val modelValue = json.opt("carModel")
        val model = when (modelValue) {
            is JSONObject -> listOf("name", "title", "model", "modification")
                .firstNotNullOfOrNull { key -> modelValue.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            is String -> modelValue
            else -> null
        }
        return Vehicle(
            id = json.optString("_id").ifBlank { json.optString("id") },
            vin = json.optString("vin").takeIf { it.isNotBlank() && it != "null" },
            imei = json.optString("imei").takeIf { it.isNotBlank() && it != "null" },
            plate = json.optString("licensePlate").takeIf { it.isNotBlank() && it != "null" },
            brand = json.optString("brand").takeIf { it.isNotBlank() && it != "null" },
            model = model
        ).also {
            if (it.id.isBlank()) throw IOException("В карточке автомобиля отсутствует id")
        }
    }

    private fun resolveImei(carId: String): String {
        if (cachedCarId == carId) cachedImei?.let { return it }
        val vehicle = getVehicle(carId)
        val imei = vehicle.imei?.takeIf { it.isNotBlank() }
            ?: throw IOException("В карточке автомобиля отсутствует telemetry id")
        cachedCarId = carId
        cachedImei = imei
        return imei
    }

    fun fetchTelemetry(carId: String? = null): Telemetry {
        val selectedCarId = carId?.takeIf { it.isNotBlank() } ?: defaultCarId()
        val now = SystemClock.elapsedRealtime()
        if (cachedCarId == selectedCarId) {
            cachedTelemetry?.takeIf { now - it.first < MIN_TELEMETRY_INTERVAL_MS }?.let { return it.second }
        }
        val imei = resolveImei(selectedCarId)
        val json = request("/client-bff-service/telemetry/${pathSegment(imei)}")
        val sensors = json.optJSONObject("sensors")
            ?: throw IOException("В ответе EVACONNECT отсутствует sensors")
        fun number(key: String): Double? = numeric(sensors.opt(key))
        val soc = number("batteryPercentage")?.takeIf { it in 0.0..100.0 }
            ?: throw IOException("SOC недоступен")
        val odometer = number("odometer") ?: throw IOException("Одометр недоступен")
        // Match the MCP public status: do not persist exact location/course.
        val safeSensors = LinkedHashMap<String, String>()
        val keys = sensors.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in GEO_SENSOR_KEYS) continue
            val value = sensors.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            safeSensors[key] = when (value) {
                is JSONObject, is JSONArray -> value.toString()
                else -> value.toString()
            }
        }
        val telemetry = Telemetry(
            soc = soc,
            odometer = odometer,
            stockRange = number("remainsBatteryMileage"),
            online = json.optBoolean("isOnline", false),
            ready = if (json.has("isCarStateReady") && json.opt("isCarStateReady") != JSONObject.NULL)
                json.optBoolean("isCarStateReady") else null,
            updatedAt = json.opt("carStateUpdatedAt")?.takeUnless { it == JSONObject.NULL }?.toString(),
            sensors = safeSensors,
            onlineState = json.opt("onlineState").takeUnless { it == JSONObject.NULL },
            warnings = json.opt("warnings").takeUnless { it == JSONObject.NULL },
            buttons = (json.optJSONArray("buttons") ?: JSONArray()).let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
        )
        cachedTelemetry = now to telemetry
        return telemetry
    }

    // --- The six read-only MCP tools, exposed as in-process Kotlin methods ---

    fun evoluteStatus(carId: String? = null, includePii: Boolean = false): JSONObject {
        val selectedCarId = carId?.takeIf { it.isNotBlank() } ?: defaultCarId()
        val telemetry = fetchTelemetry(selectedCarId)
        val sensorValues = telemetry.sensors
        fun value(key: String): Any = sensorValues[key]?.let(::coerceSensor) ?: JSONObject.NULL
        val charge = JSONObject()
            .put("battery_percentage", value("batteryPercentage"))
            .put("range", value("remainsBatteryMileage"))
            .put("charging_gun", value("isChargingGunInserted"))
            .put("battery_temp", value("batteryTemp"))
            .put("voltage_12v", value("12VBatteryVoltage"))
        val climate = JSONObject()
            .put("target", value("climateTargetTemp"))
            .put("fan", value("climateFanSpeed"))
            .put("inside", value("inBoardTemp"))
            .put("outside", value("outsideTemp"))
            .put("coolant", value("coolantTemp"))
        val body = JSONObject()
            .put("locked", value("isCentralLockingOn"))
            .put("door_fl", value("doorFLStatus"))
            .put("door_fr", value("doorFRStatus"))
            .put("door_rl", value("doorRLStatus"))
            .put("door_rr", value("doorRRStatus"))
            .put("trunk", value("trunkStatus"))
            .put("headlights", value("headLightsStatus"))
        val drive = JSONObject()
            .put("ignition", value("isIgnitionOn"))
            .put("parked", value("isParkedOn"))
            .put("odometer", telemetry.odometer)
            .put("signal", value("signalLevel"))
        return JSONObject()
            .put("online", telemetry.online)
            .put("online_state", telemetry.onlineState.jsonOrNull())
            .put("car_state_updated_at", telemetry.updatedAt ?: JSONObject.NULL)
            .put("charge", charge)
            .put("climate", climate)
            .put("body", body)
            .put("drive", drive)
            .put("buttons", JSONArray().also { array ->
                telemetry.buttons.forEach { button ->
                    array.put(JSONObject()
                        .put("title", button.opt("title").jsonOrNull())
                        .put("status", button.opt("status").jsonOrNull())
                        .put("enabled", button.opt("enabled").jsonOrNull()))
                }
            })
            .put("warnings", telemetry.warnings.jsonOrNull())
            .also {
                // includePii is explicit, but the normal mobile snapshot deliberately
                // excludes GPS from the persisted Telemetry model in all cases.
                if (includePii) it.put("location_included", false)
            }
    }

    fun evoluteVehicles(includePii: Boolean = false): JSONArray {
        val output = JSONArray()
        listVehicles().forEach { vehicle ->
            output.put(
                JSONObject()
                    .put("id", vehicle.id)
                    .put("plate", if (includePii) vehicle.plate.jsonOrNull() else maskTail(vehicle.plate, 4).jsonOrNull())
                    .put("brand", vehicle.brand.jsonOrNull())
                    .put("model", vehicle.model.jsonOrNull())
                    .put("vin", if (includePii) vehicle.vin.jsonOrNull() else maskTail(vehicle.vin, 4).jsonOrNull())
                    .put("imei", if (includePii) vehicle.imei.jsonOrNull() else maskTail(vehicle.imei, 4).jsonOrNull())
            )
        }
        return output
    }

    fun evoluteTrips(
        carId: String? = null,
        limit: Int = 5,
        offset: Int = 0,
        sortBy: String = "DATE",
        sortDir: String = "DESC",
        includePii: Boolean = false
    ): JSONObject {
        require(limit in 1..20) { "limit должен быть от 1 до 20" }
        require(offset >= 0) { "offset не может быть отрицательным" }
        require(sortBy in setOf("DATE", "DURATION", "DISTANCE")) { "Недопустимый sort_by" }
        require(sortDir in setOf("ASC", "DESC")) { "Недопустимый sort_dir" }
        val selectedCarId = carId?.takeIf { it.isNotBlank() } ?: defaultCarId()
        val page = request(
            "/car-service/travels/search/${pathSegment(selectedCarId)}",
            "POST",
            JSONObject()
                .put("sort", JSONObject().put("by", sortBy).put("dir", sortDir))
                .put("filters", JSONArray())
                .put("limit", limit)
                .put("offset", offset)
        )
        val rows = page.optJSONArray("rows") ?: JSONArray()
        val publicRows = JSONArray()
        for (i in 0 until rows.length()) {
            rows.optJSONObject(i)?.let { publicRows.put(publicTripSummary(it, includePii)) }
        }
        return JSONObject()
            .put("car_id", selectedCarId)
            .put("total", page.opt("total").jsonOrNull())
            .put("offset", page.opt("offset").jsonOrNull())
            .put("limit", page.opt("limit").jsonOrNull())
            .put("rows", publicRows)
    }

    fun evoluteTrip(
        travelId: Int,
        startTime: Long,
        carId: String? = null,
        includeTrack: Boolean = false,
        includePii: Boolean = false
    ): JSONObject {
        val selectedCarId = carId?.takeIf { it.isNotBlank() } ?: defaultCarId()
        val query = URLEncoder.encode(startTime.toString(), "UTF-8")
        val details = request(
            "/car-service/travels/details/${pathSegment(selectedCarId)}/$travelId?startTime=$query"
        )
        return publicTripDetails(details, includePii, includeTrack)
    }

    fun evoluteCharge(): JSONObject {
        val current = tokens.get() ?: throw AuthenticationRequiredException("Требуется авторизация")
        val response = execute("/charge-service/session/v2/current", access = current.access)
        if (response.code == 404 || response.body.isBlank() || response.body.trim() == "null" || response.body.trim() == "{}") {
            return JSONObject().put("session", JSONObject.NULL)
        }
        if (response.code == 401) {
            refresh(current)
            val retry = execute("/charge-service/session/v2/current", access = tokens.get()?.access)
            if (retry.code == 404 || retry.body.isBlank() || retry.body.trim() == "null" || retry.body.trim() == "{}") {
                return JSONObject().put("session", JSONObject.NULL)
            }
            if (retry.code !in 200..299) throw IOException("Не удалось получить сессию зарядки: HTTP ${retry.code}")
            return JSONObject().put("session", parseAny(retry.body))
        }
        if (response.code !in 200..299) throw IOException("Не удалось получить сессию зарядки: HTTP ${response.code}")
        return JSONObject().put("session", parseAny(response.body))
    }

    fun evoluteAuthStatus(): AuthStatus {
        val stored = runCatching { tokens.get() }.getOrNull()
        val hasAccess = !stored?.access.isNullOrBlank()
        val hasRefresh = !stored?.refresh.isNullOrBlank()
        val hasCarId = !stored?.carId.isNullOrBlank()
        if (!hasAccess && !hasRefresh) return AuthStatus(false, false, hasCarId, false)
        return try {
            val user = me()
            AuthStatus(hasAccess, hasRefresh, hasCarId, true,
                user.optString("_id").takeIf { it.isNotBlank() && it != "null" })
        } catch (_: Exception) {
            AuthStatus(hasAccess, hasRefresh, hasCarId, false)
        }
    }

    private fun publicTripSummary(trip: JSONObject, includePii: Boolean): JSONObject {
        val result = JSONObject()
        val keys = listOf(
            "id", "segmentStartTime", "segmentEndTime", "startDate", "endDate",
            "duration", "distance", "batteryConsumption", "odometer", "battery", "fuel", "title"
        )
        keys.forEach { key ->
            val outputKey = when (key) {
                "segmentStartTime" -> "segment_start_time"
                "segmentEndTime" -> "segment_end_time"
                "startDate" -> "start_date"
                "endDate" -> "end_date"
                "batteryConsumption" -> "battery_consumption"
                else -> key
            }
            val raw = trip.opt(key)
            val value = when (key) {
                "odometer", "battery", "fuel" -> firstLast(raw)
                "id", "distance", "batteryConsumption" -> raw.toString().toLongOrNull() ?: raw
                else -> raw
            }
            result.put(outputKey, value.jsonOrNull())
        }
        if (includePii) result.put("description", trip.opt("description").jsonOrNull())
        return result
    }

    private fun firstLast(value: Any?): Any {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL
        if (value !is JSONObject) return JSONObject.NULL
        return JSONObject()
            .put("first", value.opt("first").jsonOrNull())
            .put("last", value.opt("last").jsonOrNull())
    }

    private fun publicTripDetails(trip: JSONObject, includePii: Boolean, includeTrack: Boolean): JSONObject {
        val result = publicTripSummary(trip, includePii)
        result.put("points_total", trip.opt("pointsTotal").jsonOrNull())
        if (includePii) {
            result.put("start_addr", trip.opt("startAddr").jsonOrNull())
            result.put("end_addr", trip.opt("endAddr").jsonOrNull())
        }
        if (includeTrack) {
            val points = trip.optJSONArray("points") ?: JSONArray()
            val safePoints = JSONArray()
            for (i in 0 until points.length()) {
                val point = points.optJSONObject(i) ?: continue
                safePoints.put(
                    JSONObject()
                        .put("lat", point.opt("lat").jsonOrNull())
                        .put("lon", point.opt("lon").jsonOrNull())
                        .put("time", point.opt("time").jsonOrNull())
                )
            }
            result.put("points", safePoints)
        }
        return result
    }

    private fun numeric(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL || value is Boolean) return null
        val number = value.toString().toDoubleOrNull() ?: return null
        return number.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun coerceSensor(value: String): Any {
        if (value.equals("true", true)) return true
        if (value.equals("false", true)) return false
        return value.toLongOrNull() ?: value.toDoubleOrNull() ?: value
    }

    private fun maskTail(value: String?, keep: Int): String? {
        if (value == null) return null
        if (value.length <= keep) return "*".repeat(value.length)
        return "*".repeat(value.length - keep) + value.takeLast(keep)
    }

    private fun Any?.jsonOrNull(): Any = this ?: JSONObject.NULL

    private fun parseAny(body: String): Any {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JSONObject.NULL
        return when (trimmed.first()) {
            '{' -> JSONObject(trimmed)
            '[' -> JSONArray(trimmed)
            else -> trimmed
        }
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        internal val TERMINAL_REFRESH_CODES = setOf(400, 401, 403)
        private const val MIN_TELEMETRY_INTERVAL_MS = 5_000L
        private val REFRESH_LOCK = Any()
        private val GEO_SENSOR_KEYS = setOf("lat", "lon", "course")
    }
}

interface TokenStore {
    fun get(): EvaconnectMcpClient.Tokens?
    fun save(tokens: EvaconnectMcpClient.Tokens)
    fun clear()
}

/** Refresh credentials were rejected; interactive SMS sign-in is required. */
class AuthenticationRequiredException(message: String) : IOException(message)
