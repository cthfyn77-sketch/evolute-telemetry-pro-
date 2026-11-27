package com.evolute.telemetrypro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaconnectMcpClientRefreshPolicyTest {
    @Test
    fun onlyUnauthorizedRefreshCredentialsRequireInteractiveSignIn() {
        assertTrue(401 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
        assertFalse(400 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
        assertFalse(403 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
    }

    @Test
    fun transientServerFailuresRemainRetryable() {
        assertFalse(408 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
        assertFalse(429 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
        assertFalse(500 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
        assertFalse(503 in EvaconnectMcpClient.TERMINAL_REFRESH_CODES)
    }
}
