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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.evolute.telemetrypro.data.ApiClient
import com.evolute.telemetrypro.data.BatteryHistoryAnalyzer
import com.evolute.telemetrypro.data.KeystoreTokenStore
import com.evolute.telemetrypro.data.SnapshotDb
import com.evolute.telemetrypro.data.TripHistoryAnalyzer
import com.evolute.telemetrypro.data.RangeEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Graphite = Color(0xFF101820)
private val Panel = Color(0xFF1B2933)
private val Cyan = Color(0xFF28D7D0)
private val Muted = Color(0xFF9FB0BA)

private data class RefreshState(
    val snapshot: ApiClient.Telemetry?,
    val count: Long,
    val estimate: RangeEstimator.Estimate?,
    val capturedAtMs: Long?,
    val telemetryGaps: TelemetryGapAnalyzer.Report
)

class MainActivity : ComponentActivity() {
    private lateinit var tokenStore: KeystoreTokenStore
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = KeystoreTokenStore(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val existingSession = runCatching { tokenStore.get() != null }.getOrDefault(false)
        setContent {
            TelemetryProApp(
                hasSession = existingSession,
                onSessionAvailable = { runCatching { tokenStore.get() != null }.getOrDefault(false) },
                onRequestCode = { phone, country -> ApiClient(tokenStore).requestOtp(phone, country) },
                onLogin = { phone, code ->
                    ApiClient(tokenStore).signIn(phone, code)
                    ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java))
                },
                onRefresh = {
                    val db = SnapshotDb.get(this)
                    val latest = db.latest()
                    val estimator = RangeEstimator()
                    val rangeSamples = db.recentSamples()
                    rangeSamples.forEach(estimator::add)
                    val rangeSoc = if (latest?.online == true) latest.soc else rangeSamples.lastOrNull()?.socPercent
                    val rangeFallback = latest?.takeIf { it.online }?.stockRange
                    val rangeEstimate = rangeSoc?.let { estimator.estimate(it, rangeFallback) }
                    val nowMs = System.currentTimeMillis()
                    val gapReport = TelemetryGapAnalyzer.analyze(
                        db.captureTimesSince(nowMs - TelemetryGapAnalyzer.WINDOW_MS),
                        nowMs
                    )
                    RefreshState(latest, db.count(), rangeEstimate, db.latestCapturedAtMs(), gapReport)
                },
                onTripHistory = {
                    TripHistoryAnalyzer.analyze(SnapshotDb.get(this).tripHistorySamples())
                },
                onBatteryHistory = { periodMs ->
                    val db = SnapshotDb.get(this)
                    val cutoff = periodMs?.let { System.currentTimeMillis() - it }
                    BatteryHistoryAnalyzer.analyze(db.batteryHistory(cutoff))
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground resets Android's dataSync FGS quota.
        // Restart collection if Android stopped it after the background limit.
        val hasSession = runCatching { tokenStore.get() != null }.getOrDefault(false)
        if (hasSession) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java)) }
        }
    }
}

@Composable
private fun TelemetryProApp(
    hasSession: Boolean,
    onSessionAvailable: () -> Boolean,
    onRequestCode: suspend (String, String) -> Unit,
    onLogin: suspend (String, String) -> Unit,
    onRefresh: suspend () -> RefreshState,
    onTripHistory: suspend () -> TripHistoryAnalyzer.Report,
    onBatteryHistory: suspend (Long?) -> BatteryHistoryAnalyzer.Report
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
    var showAdditional by rememberSaveable { mutableStateOf(true) }
    var showTechnicalSensors by rememberSaveable { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<ApiClient.Telemetry?>(null) }
    var count by remember { mutableLongStateOf(0L) }
    var capturedAtMs by remember { mutableStateOf<Long?>(null) }
    var telemetryGaps by remember { mutableStateOf(TelemetryGapAnalyzer.Report.empty()) }
    var clockNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var estimate by remember { mutableStateOf<RangeEstimator.Estimate?>(null) }
    var batteryPeriodMs by rememberSaveable { mutableStateOf<Long?>(604_800_000L) }
    var batteryReport by remember { mutableStateOf<BatteryHistoryAnalyzer.Report?>(null) }
    var tripReport by remember { mutableStateOf<TripHistoryAnalyzer.Report?>(null) }
    var tripLoading by remember { mutableStateOf(false) }
    var batteryLoading by remember { mutableStateOf(false) }
    var refreshInFlight by remember { mutableStateOf(false) }

    fun refresh() {
        if (refreshInFlight) return
        refreshInFlight = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { onRefresh() }
                snapshot = result.snapshot
                count = result.count
                estimate = result.estimate
                capturedAtMs = result.capturedAtMs
                telemetryGaps = result.telemetryGaps
                clockNowMs = System.currentTimeMillis()
                status = if (snapshot == null) "Ожидание первого снимка телеметрии" else "Локальная история обновлена"
            } catch (e: Exception) {
                status = e.message ?: "Ошибка чтения данных"
            } finally {
                refreshInFlight = false
            }
        }
    }

    LaunchedEffect(session) {
        if (session) {
            refresh()
            while (true) {
                kotlinx.coroutines.delay(5_000)
                clockNowMs = System.currentTimeMillis()
                val available = withContext(Dispatchers.IO) { onSessionAvailable() }
                if (!available) {
                    session = false
                    status = "Сессия истекла • войдите в аккаунт заново"
                    break
                }
                refresh()
            }
        }
    }

    LaunchedEffect(session, tab, count) {
        if (session && tab == "Поездки") {
            tripLoading = true
            try {
                tripReport = withContext(Dispatchers.IO) { onTripHistory() }
            } catch (e: Exception) {
                status = e.message ?: "Не удалось прочитать историю поездок"
            } finally {
                tripLoading = false
            }
        }
    }

    LaunchedEffect(session, tab, batteryPeriodMs, count) {
        if (session && tab == "Батарея") {
            batteryLoading = true
            try {
                batteryReport = withContext(Dispatchers.IO) { onBatteryHistory(batteryPeriodMs) }
            } catch (e: Exception) {
                status = e.message ?: "Не удалось прочитать историю аккумулятора"
            } finally {
                batteryLoading = false
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
                MetricCard(
                    "Свежесть получения телеметрии",
                    if (TelemetryFreshness.isStale(capturedAtMs, clockNowMs)) "Нет новых снимков" else "Снимки сохраняются",
                    TelemetryFreshness.label(capturedAtMs, clockNowMs)
                )
                MetricCard(
                    "Обновление состояния автомобиля",
                    TelemetryFreshness.apiStateLabel(snapshot?.updatedAt, clockNowMs),
                    "Время carStateUpdatedAt из API; отдельно от времени сохранения снимка"
                )
                when (tab) {
                    "Главная" -> {
                        MetricCard("Заряд батареи", snapshot?.let { "${it.soc.toInt()}%" } ?: "—", "Последний локальный снимок")
                        MetricCard(
                            "Запас хода",
                            estimate?.let { "${it.rangeKm.toInt()} км" } ?: "— км",
                            when (estimate?.source) {
                                RangeEstimator.Source.MEASURED_1_KM -> "Расчёт по фактическому расходу SOC • среднее последних 8 км"
                                RangeEstimator.Source.OEM_FALLBACK -> "Штатный прогноз автомобиля • недостаточно истории"
                                else -> "Ожидание данных"
                            }
                        )
                        MetricCard("Одометр", snapshot?.let { "${"%.1f".format(it.odometer)} км" } ?: "—", "Штатный показатель автомобиля")
                        MetricCard(
                            "Интервалы получения снимков • 24 ч",
                            "${telemetryGaps.sampleCount} снимков за 24 ч",
                            TelemetryGapAnalyzer.dashboardDetail(telemetryGaps)
                        )
                        TextButton(onClick = { showAdditional = !showAdditional }) {
                            Text(if (showAdditional) "Скрыть дополнительные показания" else "Показать дополнительные показания")
                        }
                        if (showAdditional) {
                            MetricCard("Состояние", snapshot?.let { if (it.online) "Онлайн" else "Офлайн" } ?: "Нет данных", status)
                        }
                    }
                    "Батарея" -> BatteryHistoryPanel(
                        report = batteryReport,
                        periodMs = batteryPeriodMs,
                        onPeriodChange = { batteryPeriodMs = it },
                        loading = batteryLoading
                    )
                    "Поездки" -> {
                        val report = tripReport
                        MetricCard(
                            "История поездок",
                            when {
                                tripLoading -> "Загрузка…"
                                report == null -> "Нет данных"
                                else -> "${report.trips.size} поездок"
                            },
                            report?.let {
                                "Локальная реконструкция по одометру • ${it.sampleCount} снимков • исключено разрывов: ${it.excludedGaps}, скачков одометра: ${it.ignoredOdometerJumps}"
                            } ?: "Поездки определяются только по наблюдаемому изменению одометра"
                        )
                        if (report != null && report.trips.isEmpty()) {
                            MetricCard("Пока нет подтверждённых поездок", "—", "Нужны последовательные онлайн-снимки с увеличением одометра")
                        }
                        report?.trips?.forEachIndexed { index, trip ->
                            val start = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(trip.startedAtMs))
                            val end = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(trip.endedAtMs))
                            MetricCard(
                                "Поездка ${report.trips.size - index}",
                                "${"%.1f".format(trip.distanceKm)} км",
                                "$start–$end • SOC ${"%.1f".format(trip.startSocPercent)}% → ${"%.1f".format(trip.endSocPercent)}% • расход ${"%.1f".format(trip.socUsedPercent)} п.п."
                            )
                        }
                        if (report != null) {
                            Text("Расстояние через разрывы телеметрии и скачки одометра не засчитывается. Маршрут и адреса не отображаются: подтверждённого API маршрутов пока нет.", color = Muted, fontSize = 11.sp)
                        }
                    }
                    "Аналитика" -> MetricCard("Аналитика", "Подключается", "Расход SOC на 100 км после накопления километровой истории")
                    else -> {
                        TextButton(onClick = { showTechnicalSensors = !showTechnicalSensors }) {
                            Text(if (showTechnicalSensors) "Скрыть технические датчики" else "Показать технические датчики")
                        }
                        if (showTechnicalSensors) {
                            MetricCard("Технические датчики",
                                snapshot?.sensors?.entries?.joinToString("\\n") { "${it.key}: ${it.value}" } ?: "Нет данных",
                                "Показываются только значения, реально полученные от API")
                        }
                    }
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
private fun MetricCard(title: String, value: String, detail: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Muted)
            Text(value, color = Color.White, fontSize = 26.sp)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}
