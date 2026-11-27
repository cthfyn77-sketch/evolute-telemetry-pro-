package com.evolute.telemetrypro.data

/**
 * Reconstructs cable-connection sessions from locally captured telemetry.
 * The API field isChargingGunInserted confirms cable insertion, not charging
 * power/current; therefore the UI must describe these as connection sessions.
 */
object ChargingSessionAnalyzer {
    const val MAX_SAMPLE_GAP_MS = 90_000L

    enum class Status { CONNECTED, COMPLETED, INTERRUPTED }

    data class Session(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val startSocPercent: Double,
        val endSocPercent: Double,
        val sampleCount: Int,
        val status: Status
    ) {
        val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
        val socChangePercent: Double get() = endSocPercent - startSocPercent
    }

    data class Report(
        val sessions: List<Session>,
        val sampleCount: Int
    ) {
        val completedCount: Int get() = sessions.count { it.status == Status.COMPLETED }
        val totalSocIncreasePercent: Double
            get() = sessions.filter { it.status == Status.COMPLETED }
                .sumOf { it.socChangePercent.coerceAtLeast(0.0) }

        companion object {
            fun empty() = Report(emptyList(), 0)
        }
    }

    fun analyze(input: List<TripHistorySample>): Report {
        val samples = input.asSequence()
            .filter { it.capturedAtMs > 0L && it.socPercent.isFinite() && it.socPercent in 0.0..100.0 }
            .sortedBy { it.capturedAtMs }
            .distinctBy { it.capturedAtMs }
            .toList()
        if (samples.isEmpty()) return Report.empty()

        val sessions = mutableListOf<Session>()
        var first: TripHistorySample? = null
        var last: TripHistorySample? = null
        var count = 0

        fun finish(status: Status, endAtMs: Long? = null, endSoc: Double? = null) {
            val start = first ?: return
            val end = last ?: start
            sessions += Session(
                startedAtMs = start.capturedAtMs,
                endedAtMs = endAtMs ?: end.capturedAtMs,
                startSocPercent = start.socPercent,
                endSocPercent = endSoc ?: end.socPercent,
                sampleCount = count,
                status = status
            )
            first = null
            last = null
            count = 0
        }

        for (sample in samples) {
            val previous = last
            if (previous != null && sample.capturedAtMs - previous.capturedAtMs > MAX_SAMPLE_GAP_MS) {
                finish(Status.INTERRUPTED)
            }

            if (!sample.online) {
                finish(Status.INTERRUPTED)
                continue
            }

            if (sample.charging) {
                if (first == null) first = sample
                last = sample
                count++
            } else if (first != null) {
                finish(Status.COMPLETED, sample.capturedAtMs, sample.socPercent)
            }
        }

        if (first != null) finish(Status.CONNECTED)
        return Report(sessions.asReversed(), samples.size)
    }
}
