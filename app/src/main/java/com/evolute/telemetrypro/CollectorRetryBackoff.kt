package com.evolute.telemetrypro

/**
 * Bounded retry schedule for transient telemetry/API failures.
 *
 * A successful poll (failureCount = 0) keeps the normal 30-second cadence.
 * Consecutive failures back off to 30s, 60s, 120s, 240s, then 300s max.
 */
internal object CollectorRetryBackoff {
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 5 * 60_000L

    fun nextFailureCount(currentFailureCount: Int): Int =
        (currentFailureCount.coerceAtLeast(0) + 1).coerceAtMost(30)

    fun delayAfterFailure(failureCount: Int): Long {
        if (failureCount <= 1) return BASE_DELAY_MS
        val exponent = (failureCount - 1).coerceAtMost(4)
        return (BASE_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
    }
}
