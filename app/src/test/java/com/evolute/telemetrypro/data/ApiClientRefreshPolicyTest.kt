package com.evolute.telemetrypro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientRefreshPolicyTest {
    @Test
    fun rejectedRefreshCredentialsRequireInteractiveSignIn() {
        assertTrue(400 in ApiClient.TERMINAL_REFRESH_CODES)
        assertTrue(401 in ApiClient.TERMINAL_REFRESH_CODES)
        assertTrue(403 in ApiClient.TERMINAL_REFRESH_CODES)
    }

    @Test
    fun transientServerFailuresRemainRetryable() {
        assertFalse(408 in ApiClient.TERMINAL_REFRESH_CODES)
        assertFalse(429 in ApiClient.TERMINAL_REFRESH_CODES)
        assertFalse(500 in ApiClient.TERMINAL_REFRESH_CODES)
        assertFalse(503 in ApiClient.TERMINAL_REFRESH_CODES)
    }
}
