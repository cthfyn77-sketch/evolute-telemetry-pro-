package com.evolute.telemetrypro

/**
 * Describes gaps between locally persisted capture timestamps.
 * A long interval is a missed sampling interval, not proof of a vehicle/network outage.
 */
object TelemetryGapAnalyzer {
    const val WINDOW_MS = 24L * 60L * 60L * 1_000L
    const val GAP_THRESHOLD_MS = 90_000L

    data class Report(
        val sampleCount: Int,
        val gapsOverThreshold: Int,
        val longestGapMs: Long?,
        val latestGapMs: Long?
    ) {
        companion object {
            fun empty() = Report(0, 0, null, null)
        }
    }

    fun analyze(
        capturedAtMs: List<Long>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        gapThresholdMs: Long = GAP_THRESHOLD_MS
    ): Report {
        require(windowMs > 0L) { "windowMs must be positive" }
        require(gapThresholdMs >= 0L) { "gapThresholdMs must not be negative" }
        val cutoffMs = nowMs - windowMs
        val timestamps = capturedAtMs
            .asSequence()
            .filter { it >= cutoffMs && it <= nowMs }
            .distinct()
            .sorted()
            .toList()
        val gaps = timestamps.zipWithNext { previous, current -> current - previous }
        return Report(
            sampleCount = timestamps.size,
            gapsOverThreshold = gaps.count { it > gapThresholdMs },
            longestGapMs = gaps.maxOrNull(),
            latestGapMs = gaps.lastOrNull()
        )
    }

    fun durationLabel(durationMs: Long): String {
        val seconds = (durationMs.coerceAtLeast(0L) / 1_000L)
        return when {
            seconds >= 3_600L -> {
                val hours = seconds / 3_600L
                val minutes = (seconds % 3_600L) / 60L
                if (minutes == 0L) "$hours ч" else "$hours ч $minutes мин"
            }
            seconds >= 60L -> {
                val minutes = seconds / 60L
                val remainder = seconds % 60L
                if (remainder == 0L) "$minutes мин" else "$minutes мин $remainder с"
            }
            else -> "$seconds с"
        }
    }
}
