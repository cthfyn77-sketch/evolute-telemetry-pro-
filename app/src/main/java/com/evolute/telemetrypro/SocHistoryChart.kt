package com.evolute.telemetrypro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evolute.telemetrypro.data.ChargingSession
import com.evolute.telemetrypro.data.SocSample
import kotlin.math.abs

private fun socColor(soc: Double): Color {
    val value = (soc.coerceIn(0.0, 100.0) / 100.0).toFloat()
    return if (value <= 0.5f) {
        androidx.compose.ui.graphics.lerp(Color(0xFFE53935), Color(0xFFFFD740), value * 2f)
    } else {
        androidx.compose.ui.graphics.lerp(Color(0xFFFFD740), Color(0xFF43A047), (value - 0.5f) * 2f)
    }
}

@Composable
internal fun SocHistoryChart(
    samples: List<SocSample>,
    sessions: List<ChargingSession>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (samples.isEmpty()) {
        Box(modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text("За выбранный период снимков пока нет", color = Color(0xFF9FB0BA))
        }
        return
    }
    val firstTime = samples.first().capturedAtMs
    val lastTime = samples.last().capturedAtMs
    val span = (lastTime - firstTime).coerceAtLeast(1L).toFloat()
    Row(modifier.fillMaxWidth().height(238.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.width(38.dp).fillMaxHeight().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            listOf("100%", "75%", "50%", "25%", "0%").forEach {
                Text(it, color = Color(0xFFB7C4CC), fontSize = 10.sp)
            }
        }
        Box(
            Modifier.width(9.dp).fillMaxHeight().padding(vertical = 8.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF43A047), Color(0xFFFFD740), Color(0xFFE53935))),
                    RoundedCornerShape(6.dp)
                )
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(
                Modifier.weight(1f).fillMaxWidth().pointerInput(samples) {
                    fun indexAt(xPosition: Float): Int = samples.indices.minByOrNull { index ->
                        val pointX = if (samples.size == 1) 0f else
                            (samples[index].capturedAtMs - firstTime).toFloat() / span * size.width
                        abs(pointX - xPosition)
                    } ?: 0
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onSelect(indexAt(down.position.x))
                        var active = true
                        while (active) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                active = false
                            } else {
                                onSelect(indexAt(change.position.x))
                                change.consume()
                            }
                        }
                    }
                }
            ) {
                val left = 2f
                val right = size.width - 3f
                val top = 8f
                val bottom = size.height - 8f
                fun x(time: Long) = left + ((time - firstTime).toFloat() / span) * (right - left)
                fun y(soc: Double) = bottom - (soc.coerceIn(0.0, 100.0).toFloat() / 100f) * (bottom - top)

                for (v in listOf(0, 25, 50, 75, 100)) {
                    val yy = y(v.toDouble())
                    drawLine(Color(0xFF78909C).copy(alpha = 0.22f), Offset(left, yy), Offset(right, yy), 1f)
                }
                sessions.forEach { session ->
                    val start = session.cableInsertedAtMs
                    if (start in firstTime..lastTime) {
                        val xx = x(start)
                        drawLine(Color(0xFF29B6F6), Offset(xx, top), Offset(xx, bottom), 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                    }
                    val end = session.cableRemovedAtMs
                    if (end != null && end in firstTime..lastTime) {
                        val xx = x(end)
                        drawLine(Color(0xFF29B6F6), Offset(xx, top), Offset(xx, bottom), 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                    }
                }
                for (i in 1 until samples.size) {
                    val a = samples[i - 1]
                    val b = samples[i]
                    val dashed = b.capturedAtMs - a.capturedAtMs > 90_000L
                    drawLine(
                        color = socColor((a.soc + b.soc) / 2.0),
                        start = Offset(x(a.capturedAtMs), y(a.soc)),
                        end = Offset(x(b.capturedAtMs), y(b.soc)),
                        strokeWidth = 3.5f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(9f, 6f)) else null
                    )
                }
                val index = selectedIndex.coerceIn(samples.indices)
                val point = samples[index]
                val px = x(point.capturedAtMs)
                val py = y(point.soc)
                drawLine(Color.White.copy(alpha = 0.65f), Offset(px, top), Offset(px, bottom), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                drawCircle(Color.White, 7f, Offset(px, py))
                drawCircle(socColor(point.soc), 4.5f, Offset(px, py))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val fmt = java.time.format.DateTimeFormatter.ofPattern(
                    if (span < 2 * 86_400_000f) "HH:mm" else "dd.MM"
                ).withZone(java.time.ZoneId.systemDefault())
                Text(fmt.format(java.time.Instant.ofEpochMilli(firstTime)), color = Color(0xFF9FB0BA), fontSize = 9.sp)
                Text(fmt.format(java.time.Instant.ofEpochMilli(lastTime)), color = Color(0xFF9FB0BA), fontSize = 9.sp)
            }
        }
    }
}
