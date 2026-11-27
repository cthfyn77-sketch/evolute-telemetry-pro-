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
    fun invalidFailureCountsDoNotProduceZeroOrNegativeDelay() {
        assertEquals(30_000L, CollectorRetryBackoff.delayAfterFailure(-1))
    }
}
