package com.evolute.telemetrypro.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Immutable input row for battery analytics; source snapshots are never modified. */
data class BatteryHistorySample(
    val capturedAt: String,
    val soc: Double,
    val odometer: Double,
    val sensorsJson: String
)

internal object BatteryHistoryAnalyzer {
    data class Point(val at: Instant, val value: Double, val odometer: Double? = null)
    data class Metric(
        val path: String,
        val latest: Double,
        val min: Double,
        val max: Double,
        val samples: Int,
        val points: List<Point>
    )
    data class Report(val metrics: List<Metric>, val alerts: List<String>)

    private val terms = listOf(
        "voltage", "volt", "temperature", "temp", "coolant", "cabin", "interior",
        "salon", "ambient", "outside", "outdoor", "climate", "hvac", "setpoint",
        "settemp", "targettemp", "desiredtemp", "airtemp", "soh", "stateofhealth",
        "batteryhealth", "current", "amp", "soc", "stateofcharge", "chargelevel"
    )

    fun analyze(history: List<BatteryHistorySample>): Report {
        val series = linkedMapOf<String, MutableList<Point>>()
        val socPath = history.asSequence()
            .map { extract(it.sensorsJson).keys.firstOrNull(::isSocPath) }
            .firstOrNull { it != null } ?: "SOC"

        history.forEach { sample ->
            val at = runCatching { Instant.parse(sample.capturedAt) }.getOrNull() ?: return@forEach
            val values = extract(sample.sensorsJson)
            values.forEach { (path, value) ->
                series.getOrPut(path) { mutableListOf() }.add(
                    Point(at, value, sample.odometer.takeIf { it.isFinite() && it >= 0.0 })
                )
            }
            if (values.keys.none(::isSocPath) && sample.soc.isFinite() && sample.soc in 0.0..100.0) {
                series.getOrPut(socPath) { mutableListOf() }.add(
                    Point(at, sample.soc, sample.odometer.takeIf { it.isFinite() && it >= 0.0 })
                )
            }
        }

        val alerts = mutableListOf<String>()
        val metrics = series.mapNotNull { (path, raw) ->
            val ordered = raw.sortedBy { it.at }
            if (ordered.isEmpty()) return@mapNotNull null
            val rejected = isolatedSpikeIndexes(ordered)
            rejected.forEach { i ->
                alerts += "$path: одиночный скачок ${fmt(ordered[i].value)} — исключён из графика как вероятная ошибка телеметрии"
            }
            val trusted = ordered.filterIndexed { i, _ -> i !in rejected }.ifEmpty { ordered }
            Metric(path, trusted.last().value, trusted.minOf { it.value },
                trusted.maxOf { it.value }, trusted.size, trusted)
        }.sortedBy { it.path.lowercase(Locale.ROOT) }
        return Report(metrics, alerts.take(8))
    }

    private fun isSocPath(path: String): Boolean =
        path.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            .let { it.contains("soc") || it.contains("stateofcharge") || it.contains("chargelevel") }

    private fun isolatedSpikeIndexes(points: List<Point>): Set<Int> {
        val rejected = linkedSetOf<Int>()
        for (i in 1 until points.lastIndex) {
            val before = points[i - 1]
            val spike = points[i]
            val after = points[i + 1]
            if (Duration.between(before.at, spike.at) > Duration.ofMinutes(3) ||
                Duration.between(spike.at, after.at) > Duration.ofMinutes(3)) continue
            val baseline = before.value
            val deviates = abs(spike.value - baseline) >= max(abs(baseline) * 0.20, 1.0)
            val returns = abs(after.value - baseline) <= max(abs(baseline) * 0.05, 0.5)
            if (deviates && returns) rejected += i
        }
        return rejected
    }

    fun hasTelemetryGap(points: List<Point>): Boolean =
        points.zipWithNext().any { Duration.between(it.first.at, it.second.at) > Duration.ofMinutes(10) }

    private fun extract(raw: String): Map<String, Double> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val result = linkedMapOf<String, Double>()
            fun walk(value: Any?, path: String, depth: Int) {
                if (depth > 6 || result.size >= 300) return
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            walk(value.opt(key), if (path.isEmpty()) key else "$path.$key", depth + 1)
                        }
                    }
                    is JSONArray -> for (i in 0 until minOf(value.length(), 12)) walk(value.opt(i), "$path[$i]", depth + 1)
                    null, JSONObject.NULL, is Boolean -> Unit
                    else -> {
                        val normalized = path.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        if (terms.any(normalized::contains)) {
                            val number = value.toString().removeSuffix("%").toDoubleOrNull()
                            if (number != null && number.isFinite()) result[path] = number
                        }
                    }
                }
            }
            walk(JSONObject(raw), "", 0)
            result
        } catch (_: Exception) { emptyMap() }
    }

    private fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
}
