package com.evolute.telemetrypro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.evolute.telemetrypro.data.ApiClient
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.IOException
import com.evolute.telemetrypro.data.KeystoreTokenStore
import com.evolute.telemetrypro.data.SnapshotDb
import com.evolute.telemetrypro.data.RangeEstimator
import com.evolute.telemetrypro.data.TripRecord
import com.evolute.telemetrypro.data.TripMetrics
import com.evolute.telemetrypro.data.TripRoutePoint
import com.evolute.telemetrypro.data.ChargingSession
import com.evolute.telemetrypro.data.SocSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Graphite = Color(0xFF101820)
private val Panel = Color(0xFF1B2933)
private val Cyan = Color(0xFF28D7D0)
private val Muted = Color(0xFF9FB0BA)

class MainActivity : ComponentActivity() {
    private lateinit var tokenStore: KeystoreTokenStore

    private fun ensurePython() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
    }

    private fun requestSms(phone: String, country: String) {
        require(phone.length in 8..15) { "Введите телефон с кодом страны" }
        ensurePython()
        val raw = Python.getInstance().getModule("evaconnect_android")
            .callAttr("request_otp", phone.trim().removePrefix("+"), country.trim().uppercase()).toString()
        val result = JSONObject(raw)
        if (!result.optBoolean("ok", false)) throw IOException(result.optString("error", "Не удалось отправить SMS"))
    }

    private fun signIn(phone: String, code: String) {
        ensurePython()
        val raw = Python.getInstance().getModule("evaconnect_android")
            .callAttr("sign_in", phone.trim().removePrefix("+"), code.trim()).toString()
        val result = JSONObject(raw)
        if (!result.optBoolean("ok", false)) throw IOException(result.optString("error", "Ошибка SMS-авторизации"))
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw IOException("EVACONNECT не вернул пару токенов")
        tokenStore.save(ApiClient.Tokens(access, refresh,
            result.optString("user_id").takeIf { it.isNotBlank() },
            result.optString("user_token").takeIf { it.isNotBlank() },
            result.optString("widget_id").takeIf { it.isNotBlank() }))
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val gpsPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction("ENABLE_GPS"))
        }
    }

    private fun enableGpsRoutes() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction("ENABLE_GPS"))
        } else {
            gpsPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = KeystoreTokenStore(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val existingSession = runCatching { tokenStore.get() != null }.getOrDefault(false)
        if (existingSession) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java)) }
        }
        setContent {
            TelemetryProApp(
                hasSession = existingSession,
                onRequestCode = { phone, country -> requestSms(phone, country) },
                onLogin = { phone, code ->
                    signIn(phone, code)
                    ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java))
                },
                onTrips = { SnapshotDb.get(this).recentTrips(30) },
                onTripMetrics = { trip -> SnapshotDb.get(this).tripMetrics(trip) },
                onTripRoute = { trip -> SnapshotDb.get(this).tripRoutePoints(trip.id) },
                onActiveTripRoute = { val db = SnapshotDb.get(this); db.activeTrip()?.let { db.tripRoutePoints(it.id) } ?: emptyList() },
                onEnableGps = { enableGpsRoutes() },
                onChargingSessions = { SnapshotDb.get(this).recentChargingSessions(30) },
                onSocHistory = { sinceMs -> SnapshotDb.get(this).recentSocSamples(sinceMs) },
                onRefresh = {
                    val db = SnapshotDb.get(this)
                    val latest = db.latest()
                    val estimator = RangeEstimator()
                    db.recentSamples().forEach(estimator::add)
                    Triple(latest, db.count(), latest?.let { estimator.estimate(it.soc, it.stockRange) })
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val hasSession = runCatching { tokenStore.get() != null }.getOrDefault(false)
        if (hasSession) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java)) }
        }
    }
}

@Composable
private fun TelemetryProApp(
    hasSession: Boolean,
    onRequestCode: suspend (String, String) -> Unit,
    onLogin: suspend (String, String) -> Unit,
    onTrips: suspend () -> List<TripRecord>,
    onTripMetrics: suspend (TripRecord) -> TripMetrics,
    onTripRoute: suspend (TripRecord) -> List<TripRoutePoint>,
    onActiveTripRoute: suspend () -> List<TripRoutePoint>,
    onEnableGps: () -> Unit,
    onChargingSessions: suspend () -> List<ChargingSession>,
    onSocHistory: suspend (Long) -> List<SocSample>,
    onRefresh: suspend () -> Triple<ApiClient.Telemetry?, Long, RangeEstimator.Estimate?>
) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("RU") }
    var code by remember { mutableStateOf("") }
    var session by remember { mutableStateOf(hasSession) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (hasSession) "Сессия найдена" else "Войдите в аккаунт Evolute") }
    var smsSent by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Главная") }
    var snapshot by remember { mutableStateOf<ApiClient.Telemetry?>(null) }
    var count by remember { mutableLongStateOf(0L) }
    var estimate by remember { mutableStateOf<RangeEstimator.Estimate?>(null) }
    var trips by remember { mutableStateOf<List<TripRecord>>(emptyList()) }
    var selectedTrip by remember { mutableStateOf<TripRecord?>(null) }
    var selectedTripMetrics by remember { mutableStateOf<TripMetrics?>(null) }
    var selectedTripRoute by remember { mutableStateOf<List<TripRoutePoint>>(emptyList()) }
    var activeTripRoute by remember { mutableStateOf<List<TripRoutePoint>>(emptyList()) }
    var chargingSessions by remember { mutableStateOf<List<ChargingSession>>(emptyList()) }
    var socSamples by remember { mutableStateOf<List<SocSample>>(emptyList()) }
    var socPeriodDays by remember { mutableIntStateOf(1) }
    var selectedSocIndex by remember { mutableIntStateOf(0) }

    fun refresh() {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { onRefresh() }
                snapshot = result.first
                count = result.second
                estimate = result.third
                trips = withContext(Dispatchers.IO) { onTrips() }
                activeTripRoute = withContext(Dispatchers.IO) { onActiveTripRoute() }
                chargingSessions = withContext(Dispatchers.IO) { onChargingSessions() }
                status = if (snapshot == null) "Ожидание первого снимка телеметрии" else "Данные обновлены локально"
            } catch (e: Exception) { status = e.message ?: "Ошибка чтения данных" }
        }
    }

    LaunchedEffect(session) {
        if (session) {
            refresh()
            while (true) { kotlinx.coroutines.delay(5_000); refresh() }
        }
    }

    LaunchedEffect(session, tab, socPeriodDays) {
        if (session && tab == "Батарея") {
            try {
                val since = System.currentTimeMillis() - socPeriodDays.toLong() * 86_400_000L
                socSamples = withContext(Dispatchers.IO) { onSocHistory(since) }
                selectedSocIndex = (socSamples.size - 1).coerceAtLeast(0)
            } catch (e: Exception) {
                status = e.message ?: "Не удалось загрузить историю SOC"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Graphite, surface = Panel, primary = Cyan,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(Modifier.fillMaxSize().background(Graphite).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("EVOLUTE", color = Cyan, fontSize = 13.sp)
            Text("Telemetry Pro", color = Color.White, fontSize = 28.sp)
            Text("i-JOY • локальная телеметрия", color = Muted)
            Spacer(Modifier.height(18.dp))
            if (!session) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Авторизация Evolute", color = Cyan, fontSize = 18.sp)
                        OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(15) }, label = { Text("Телефон с кодом страны") }, singleLine = true)
                        OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Код страны") }, singleLine = true)
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { onRequestCode(phone, country) }
                                    smsSent = true; status = "SMS-код запрошен"
                                } catch (e: Exception) { status = e.message ?: "Не удалось запросить код" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "Подождите…" else "Запросить SMS-код") }
                        if (smsSent) {
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("Код из SMS") }, singleLine = true)
                            Button(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { onLogin(phone, code) }
                                        session = true
                                        status = "Авторизация успешна • сбор запущен автоматически"
                                    } catch (e: Exception) { status = e.message ?: "Ошибка входа" }
                                    finally { busy = false }
                                }
                            }) { Text("Войти и начать сбор") }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Главная", "Батарея", "Поездки", "Аналитика", "Техника").forEach {
                        FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it, fontSize = 10.sp) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    "Главная" -> {
                        MetricCard("Заряд батареи", snapshot?.let { "${it.soc.toInt()}%" } ?: "—", "Последний локальный снимок")
                        MetricCard(
                            "Запас хода",
                            estimate?.let { "${it.rangeKm.toInt()} км" } ?: "— км",
                            when (estimate?.source) {
                                RangeEstimator.Source.MEASURED_10_KM -> "Расчёт по фактическому расходу SOC за последние 10 км"
                                RangeEstimator.Source.OEM_FALLBACK -> "Штатный прогноз автомобиля • недостаточно истории"
                                else -> "Ожидание данных"
                            }
                        )
                        MetricCard("Одометр", snapshot?.let { "${"%.1f".format(it.odometer)} км" } ?: "—", "Штатный показатель автомобиля")
                        MetricCard("Состояние", snapshot?.let { if (it.online) "Онлайн" else "Офлайн" } ?: "Нет данных", status)
                    }
                    "Батарея" -> {
                        MetricCard("Батарея", snapshot?.let { "SOC ${it.soc.toInt()}%" } ?: "Нет данных",
                            "Снимков сохранено: $count • история SOC по локальным снимкам")
                        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("История заряда", color = Color.White, fontSize = 18.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(1 to "24 часа", 7 to "7 дней", 30 to "30 дней").forEach { (days, label) ->
                                        FilterChip(selected = socPeriodDays == days,
                                            onClick = { socPeriodDays = days },
                                            label = { Text(label, fontSize = 11.sp) })
                                    }
                                }
                                val selected = socSamples.getOrNull(selectedSocIndex.coerceIn(0, (socSamples.size - 1).coerceAtLeast(0)))
                                SocHistoryChart(socSamples, chargingSessions, selectedSocIndex,
                                    onSelect = { selectedSocIndex = it })
                                if (selected != null) {
                                    Divider(color = Color(0xFF344650))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Выбранный снимок", color = Cyan, fontSize = 13.sp)
                                    Text("${java.time.Instant.ofEpochMilli(selected.capturedAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')}",
                                        color = Color.White, fontSize = 14.sp)
                                    Text("SOC ${"%.1f".format(selected.soc)}% • Одометр ${"%.1f".format(selected.odometer)} км",
                                        color = Color.White, fontSize = 13.sp)
                                    val vehicleState = when {
                                        !selected.online -> "Офлайн"
                                        selected.ready -> "READY • автомобиль готов"
                                        else -> "Стоянка"
                                    }
                                    Text("Состояние: $vehicleState • Зарядка: ${if (selected.charging) "Подключена" else "Не подключена"}",
                                        color = Muted, fontSize = 12.sp)
                                    Text("Температура батареи: ${selected.batteryTemp?.let { "${"%.1f".format(it)} °C" } ?: "—"}",
                                        color = Muted, fontSize = 12.sp)
                                    if (selectedIndexInGap(socSamples, selectedSocIndex)) {
                                        Text("Пунктир показывает интервал без снимков; значение между точками не измерялось.",
                                            color = Color(0xFFFFD180), fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(enabled = selectedSocIndex > 0,
                                            onClick = { selectedSocIndex = (selectedSocIndex - 1).coerceAtLeast(0) }) { Text("← Предыдущий") }
                                        TextButton(enabled = selectedSocIndex < socSamples.lastIndex,
                                            onClick = { selectedSocIndex = (selectedSocIndex + 1).coerceAtMost(socSamples.lastIndex) }) { Text("Следующий →") }
                                    }
                                }
                                Text("Сплошная линия — последовательные снимки; цвет отражает SOC. Пунктир — перерыв телеметрии более 90 секунд. Голубые отметки — подключение и отключение кабеля.",
                                    color = Muted, fontSize = 11.sp)
                            }
                        }
                        MetricCard("Зарядные сессии", "${chargingSessions.size}", "Кабель и передача энергии учитываются отдельно")
                        if (chargingSessions.isEmpty()) {
                            MetricCard("История пока пуста", "Ожидание подключения", "Сессия будет записана при первом обнаружении подключённого разъёма")
                        } else {
                            chargingSessions.forEach { charge ->
                                fun time(ms: Long?): String = ms?.let {
                                    java.time.Instant.ofEpochMilli(it).toString().take(16).replace('T', ' ')
                                } ?: "—"
                                fun temp(value: Double?): String = value?.let { "${"%.1f".format(it)} °C" } ?: "—"
                                fun duration(ms: Long): String = "${ms / 3_600_000} ч ${(ms / 60_000) % 60} мин"
                                val cableDuration = charge.durationMs?.let(::duration) ?: "кабель подключён"
                                val transferDuration = duration(charge.energyDurationMs)
                                val before = (charge.socBeforeEnergy ?: charge.cableInsertedSoc)?.let { "${"%.0f".format(it)}%" } ?: "—"
                                val after = charge.socAfterEnergy?.let { "${"%.0f".format(it)}%" } ?: if (charge.cableRemovedAtMs == null) "ожидание" else "—"
                                val gain = charge.socGain?.let { "${if (it >= 0) "+" else ""}${"%.1f".format(it)} п.п." } ?: "—"
                                val sensorInfo = if (charge.hasEnergyStateSensor) "Индикатор передачи энергии найден" else "Отдельный индикатор передачи энергии пока не распознан"
                                MetricCard(
                                    time(charge.cableInsertedAtMs),
                                    "Кабель: $cableDuration • Передача энергии: $transferDuration",
                                    "Подключение: ${time(charge.cableInsertedAtMs)} • Первая передача: ${time(charge.energyStartedAtMs)} • Последняя остановка: ${time(charge.energyStoppedAtMs)} • Отключение: ${time(charge.cableRemovedAtMs)}\nSOC: $before → $after ($gain) • Температура: ${temp(charge.batteryTempBefore)} → ${temp(charge.batteryTempAfter)}\n$sensorInfo"
                                )
                            }
                        }
                    }
                    "Поездки" -> {
                        if (selectedTrip != null) {
                            val trip = selectedTrip!!
                            val metrics = selectedTripMetrics
                            fun dateTime(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                            fun number(value: Double?, suffix: String): String =
                                value?.let { "${"%.1f".format(it)}$suffix" } ?: "—"
                            TextButton(onClick = {
                                selectedTrip = null
                                selectedTripMetrics = null
                                selectedTripRoute = emptyList()
                            }) { Text("← К списку поездок") }
                            Text("Подробности поездки", color = Cyan, fontSize = 19.sp)
                            if (selectedTripRoute.isNotEmpty()) TripRouteMap(selectedTripRoute, live = trip.endedAtMs == null)
                            else Text("GPS-маршрут пока не записан или геопозиция была недоступна.", color = Muted, fontSize = 12.sp)
                            MetricCard("Время начала", dateTime(trip.startedAtMs), "Локальное время устройства")
                            MetricCard("Время окончания", trip.endedAtMs?.let(::dateTime) ?: "Поездка продолжается",
                                if (trip.endedAtMs == null) "Ожидание завершения" else "Локальное время устройства")
                            MetricCard("Пройденное расстояние", number(trip.distanceKm, " км"), "По данным одометра")
                            MetricCard("Средняя скорость", number(metrics?.averageSpeedKmh, " км/ч"),
                                "Расстояние поездки / полное время поездки")
                            MetricCard("Начальный SOC", "${"%.1f".format(trip.startSoc)}%", "Заряд в начале поездки")
                            MetricCard("Конечный SOC", "${"%.1f".format(trip.endSoc)}%", "Заряд в конце поездки")
                            MetricCard("Потраченная энергия", number(metrics?.energyUsedKwh, " кВт·ч"),
                                "Оценка по изменению SOC при расчётной ёмкости батареи 52 кВт·ч")
                            MetricCard("Температура батареи",
                                "Мин. ${number(metrics?.minBatteryTempC, " °C")}",
                                "Средняя: ${number(metrics?.averageBatteryTempC, " °C")} • Максимальная: ${number(metrics?.maxBatteryTempC, " °C")} • Замеров: ${metrics?.temperatureSamples ?: 0}")
                            MetricCard("Средний расход", number(metrics?.consumptionKwhPer100Km, " кВт·ч/100 км"),
                                "Расчётная потраченная энергия / расстояние × 100")
                            if (metrics?.energyUsedKwh == null) {
                                Text("Расход энергии не рассчитан: SOC не показывает чистое снижение за поездку или нет достаточных данных.",
                                    color = Muted, fontSize = 11.sp)
                            }
                        } else {
                            Button(onClick = onEnableGps, modifier = Modifier.fillMaxWidth()) { Text("Включить запись GPS-маршрутов") }
                            Text("GPS-точки сохраняются каждые 5 секунд при смещении от 5 м и только во время активной поездки. Для точного маршрута разрешите точную геопозицию.", color = Muted, fontSize = 11.sp)
                            if (activeTripRoute.isNotEmpty()) {
                                Text("Текущий маршрут", color = Cyan, fontSize = 17.sp)
                                TripRouteMap(activeTripRoute, live = true)
                            }
                            MetricCard("Журнал поездок", "${trips.size}", "Начало по READY и движению; GPS-подтверждение — 500 м")
                            if (trips.isEmpty()) {
                                MetricCard("Поездок пока нет", "Ожидание движения", "Запись начнётся при READY ON и подтверждённом движении по одометру или GPS")
                            } else {
                                trips.forEach { trip ->
                                    val date = java.time.Instant.ofEpochMilli(trip.startedAtMs)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                                    val state = when {
                                        trip.endedAtMs != null && trip.status == "interrupted" -> "Завершена с разрывом данных"
                                        trip.endedAtMs != null -> "Завершена"
                                        trip.status == "charging" -> "Ожидание во время подключённой зарядки"
                                        trip.status == "waiting_after_charge" -> "Окно возобновления после зарядки"
                                        trip.status == "waiting_ready_off" -> "Ожидание завершения (READY OFF)"
                                        else -> "Поездка продолжается"
                                    }
                                    MetricCard(
                                        date,
                                        "${"%.1f".format(trip.distanceKm)} км",
                                        "${"%.0f".format(trip.startSoc)}% → ${"%.0f".format(trip.endSoc)}% • $state • Нажмите для подробностей",
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val loaded = withContext(Dispatchers.IO) { onTripMetrics(trip) }
                                                    val route = withContext(Dispatchers.IO) { onTripRoute(trip) }
                                                    selectedTrip = trip
                                                    selectedTripMetrics = loaded
                                                    selectedTripRoute = route
                                                } catch (e: Exception) {
                                                    status = e.message ?: "Не удалось загрузить показатели поездки"
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    "Аналитика" -> MetricCard("Аналитика", "Подключается", "Расход SOC на 100 км после накопления километровой истории")
                    else -> MetricCard("Технические датчики",
                        snapshot?.sensors?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "Нет данных",
                        "Показываются только значения, реально полученные от API")
                }
                TextButton(onClick = { refresh() }) { Text("Обновить данные") }
                Text("Снимков в локальной базе: $count", color = Muted)
            }
            Spacer(Modifier.height(12.dp))
            Text(status, color = Muted, fontSize = 12.sp)
            Text("Только чтение API • токены зашифрованы Android Keystore • данные хранятся локально", color = Muted, fontSize = 11.sp)
        }
    }
}


@Composable
private fun TripRouteMap(points: List<TripRoutePoint>, live: Boolean) {
    val pointsJson = remember(points) {
        org.json.JSONArray().apply {
            points.forEach { point -> put(org.json.JSONArray().put(point.latitude).put(point.longitude)) }
        }.toString()
    }
    val html = remember(pointsJson, live) {
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>html,body,#map{height:100%;margin:0;background:#101820}.leaflet-container{background:#101820}</style>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head>
        <body><div id="map"></div><script>
        const pts=$pointsJson;
        const map=L.map('map',{zoomControl:true}).setView([48.85,2.35],5);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{
          maxZoom:19, attribution:'&copy; OpenStreetMap contributors'
        }).addTo(map);
        if(pts.length){
          const line=L.polyline(pts,{color:'#28d7d0',weight:5,opacity:0.9}).addTo(map);
          L.circleMarker(pts[0],{radius:7,color:'#fff',fillColor:'#16a34a',fillOpacity:1}).addTo(map).bindPopup('Начало');
          L.circleMarker(pts[pts.length-1],{radius:7,color:'#fff',fillColor:'#ef4444',fillOpacity:1}).addTo(map).bindPopup('${if (live) "Текущее положение" else "Окончание"}');
          ${if (live) "map.setView(pts[pts.length-1],16);" else "map.fitBounds(line.getBounds().pad(0.15));"}
        }
        </script></body></html>
        """.trimIndent()
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.rgb(16, 24, 32))
            }
        },
        update = { view ->
            val key = html.hashCode()
            if (view.tag != key) {
                view.tag = key
                view.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(280.dp)
    )
}

private fun selectedIndexInGap(samples: List<SocSample>, index: Int): Boolean =
    index > 0 && index < samples.size && samples[index].gapBefore

@Composable
private fun MetricCard(title: String, value: String, detail: String, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(enabled = onClick != null) {
        onClick?.invoke()
    }, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Muted)
            Text(value, color = Color.White, fontSize = 26.sp)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}
