package com.evolute.telemetrypro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Graphite = Color(0xFF101820)
private val Panel = Color(0xFF1B2933)
private val Cyan = Color(0xFF28D7D0)
private val Muted = Color(0xFF9FB0BA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TelemetryProApp() }
    }
}

@Composable
private fun TelemetryProApp() {
    var selected by remember { mutableStateOf("Главная") }
    MaterialTheme(colorScheme = darkColorScheme(
        background = Graphite, surface = Panel, primary = Cyan,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Column(Modifier.fillMaxSize().background(Graphite).padding(18.dp)) {
            Text("EVOLUTE", color = Cyan, fontSize = 13.sp)
            Text("Telemetry Pro", color = Color.White, fontSize = 28.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Главная", "Батарея", "Поездки", "Аналитика", "Техника").forEach {
                    FilterChip(selected == it, onClick = { selected = it }, label = { Text(it) })
                }
            }
            Spacer(Modifier.height(18.dp))
            when (selected) {
                "Главная" -> {
                    MetricCard("Заряд батареи", "—", "Ожидание авторизации и телеметрии")
                    MetricCard("Запас хода", "— км", "Сначала штатный прогноз, затем расчёт по 10 км")
                    MetricCard("Одометр", "— км", "Нет данных")
                }
                "Батарея" -> MetricCard("История заряда", "—", "График SOC и зарядные сессии появятся после подключения API")
                "Поездки" -> MetricCard("Поездки", "—", "История будет загружаться из аккаунта Evolute")
                "Аналитика" -> MetricCard("Эффективность", "—", "Расход SOC на 100 км после накопления истории")
                else -> MetricCard("Техническая телеметрия", "—", "Отображаются только реально полученные датчики")
            }
            Spacer(Modifier.height(16.dp))
            Text("Подключение аккаунта", color = Cyan, fontSize = 18.sp)
            Text("Экран авторизации и автоматический сбор подключим следующим этапом. Сейчас это каркас интерфейса.", color = Muted)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Muted)
            Text(value, color = Color.White, fontSize = 30.sp)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}
