package com.evolute.telemetrypro

import java.time.Instant
import java.time.OffsetDateTime

/** Describes the age of the newest locally saved telemetry sample, not vehicle ignition state. */
object TelemetryFreshness {
    const val STALE_AFTER_MS = 90_000L

    fun label(capturedAtMs: Long?, nowMs: Long): String {
        if (capturedAtMs == null) return "Ожидание первого снимка телеметрии"
        val ageMs = (nowMs - capturedAtMs).coerceAtLeast(0L)
        val ageSeconds = ageMs / 1_000L
        val ageText = if (ageSeconds < 60L) {
            "$ageSeconds с назад"
        } else {
            "${ageSeconds / 60L} мин назад"
        }
        return if (ageMs > STALE_AFTER_MS) {
            "Нет новых данных $ageText • возможна потеря связи или остановка сбора"
        } else {
            "Последний снимок: $ageText"
        }
    }

    fun isStale(capturedAtMs: Long?, nowMs: Long): Boolean =
        capturedAtMs == null || (nowMs - capturedAtMs).coerceAtLeast(0L) > STALE_AFTER_MS

    /** Shows the source timestamp separately; a parked vehicle may legitimately have an older state timestamp. */
    fun apiStateLabel(updatedAt: String?, nowMs: Long): String {
        if (updatedAt.isNullOrBlank()) return "API не передал время обновления состояния"
        val instant = runCatching { Instant.parse(updatedAt) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(updatedAt).toInstant() }.getOrNull()
            ?: return "Время API: $updatedAt"
        val ageSeconds = ((nowMs - instant.toEpochMilli()).coerceAtLeast(0L)) / 1_000L
        val ageText = if (ageSeconds < 60L) "$ageSeconds с назад" else "${ageSeconds / 60L} мин назад"
        return "Последнее обновление по API: $ageText"
    }
}
