package com.evolute.telemetrypro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolute.telemetrypro.data.BatteryHistoryAnalyzer
import java.time.Duration

private val BatteryPanel = Color(0xFF1B2933)
private val BatteryAccent = Color(0xFF28D7D0)
private val BatteryMuted = Color(0xFF9FB0BA)

@Composable
internal fun BatteryHistoryPanel(
    report: BatteryHistoryAnalyzer.Report?,
    periodMs: Long?,
    onPeriodChange: (Long?) -> Unit,
    loading: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("История аккумулятора", color = BatteryAccent, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("24 ч" to 86_400_000L, "7 дней" to 604_800_000L, "30 дней" to 2_592_000_000L, "Всё" to null)
                .forEach { (label, value) ->
                    FilterChip(
                        selected = periodMs == value,
                        onClick = { onPeriodChange(value) },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
        }
        if (loading) Text("Загружаем историю…", color = BatteryMuted)
        val metrics = report?.metrics.orEmpty()
        if (metrics.isEmpty() && !loading) {
            Text("Пока недостаточно сохранённых числовых показаний батареи для графиков.", color = BatteryMuted)
        }
        metrics.take(10).forEach { metric ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BatteryPanel)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(metric.path, color = BatteryMuted, fontSize = 12.sp)
                    Text("Сейчас: ${"%.2f".format(metric.latest)}", color = Color.White, fontSize = 21.sp)
                    Text("Мин. ${"%.2f".format(metric.min)}  •  Макс. ${"%.2f".format(metric.max)}  •  ${metric.samples} замеров",
                        color = BatteryMuted, fontSize = 11.sp)
                    BatteryMetricGraph(metric.points)
                    if (BatteryHistoryAnalyzer.hasTelemetryGap(metric.points)) {
                        Text("Есть пропуски телеметрии — линии на графике не соединяются через разрыв",
                            color = BatteryMuted, fontSize = 10.sp)
                    }
                }
            }
        }
        report?.alerts?.takeIf { it.isNotEmpty() }?.let { alerts ->
            Card(colors = CardDefaults.cardColors(containerColor = BatteryPanel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Качество данных", color = BatteryAccent)
                    alerts.forEach { Text(it, color = BatteryMuted, fontSize = 11.sp) }
                }
            }
        }
        if (report != null && report.metrics.isNotEmpty() && report.alerts.isEmpty()) {
            Text("Одиночных скачков, соответствующих критериям фильтрации, не обнаружено.",
                color = BatteryMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BatteryMetricGraph(points: List<BatteryHistoryAnalyzer.Point>) {
    Canvas(Modifier.fillMaxWidth().height(116.dp).background(Color(0xFF14212A), RoundedCornerShape(10.dp))) {
        if (points.isEmpty()) return@Canvas
        val minValue = points.minOf { it.value }
        val maxValue = points.maxOf { it.value }
        val span = (maxValue - minValue).coerceAtLeast(0.001)
        val first = points.first().at.toEpochMilli().toDouble()
        val timeSpan = (points.last().at.toEpochMilli().toDouble() - first).coerceAtLeast(1.0)
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = left + ((point.at.toEpochMilli() - first) / timeSpan).toFloat() * (right - left)
            val y = bottom - ((point.value - minValue) / span).toFloat() * (bottom - top)
            val gap = index > 0 && Duration.between(points[index - 1].at, point.at).seconds > 600
            if (index == 0 || gap) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, BatteryAccent, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.dp.toPx(), cap = StrokeCap.Round
        ))
    }
}
