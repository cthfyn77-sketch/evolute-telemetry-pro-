package com.evolute.telemetrypro

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectorRetryBackoffTest {
    @Test
    fun successfulPollKeepsNormalThirtySecondCadence() {
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(0))
    }

    @Test
    fun consecutiveFailuresUseExponentialBackoff() {
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(1))
        assertEquals(60_000L, CollectorRetryBackoff.delayAfterFailure(2))
        assertEquals(120_000L, CollectorRetryBackoff.delayAfterFailure(3))
        assertEquals(240_000L, CollectorRetryBackoff.delayAfterFailure(4))
    }

    @Test
    fun retryDelayIsCappedAtFiveMinutes() {
        assertEquals(300_000L, CollectorRetryBackoff.delayAfterFailure(5))
        assertEquals(300_000L, CollectorRetryBackoff.delayAfterFailure(30))
    }

    @Test
    fun networkRecoveryResetsFailureStreakAndRestoresNormalCadence() {
        var failures = 0
        repeat(4) {
            failures = CollectorRetryBackoff.nextFailureCount(failures)
        }
        assertEquals(4, failures)
        assertEquals(240_000L, CollectorRetryBackoff.delayAfterFailure(failures))

        // A successful API poll clears the streak, so the next outage starts at 30 seconds.
        failures = 0
        failures = CollectorRetryBackoff.nextFailureCount(failures)
        assertEquals(1, failures)
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(failures))
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(0))
    }

    @Test
    fun failureCounterIsBounded() {
        assertEquals(30, CollectorRetryBackoff.nextFailureCount(30))
        assertEquals(1, CollectorRetryBackoff.nextFailureCount(-1))
    }

    @Test
    fun invalidFailureCountsDoNotProduceZeroOrNegativeDelay() {
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(-1))
    }
}
