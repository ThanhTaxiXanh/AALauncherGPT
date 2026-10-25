package com.cartaxi.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStartRetryTest {
    @Test fun failuresHaveBoundedExponentialBackoff() {
        val retry = MapStartRetry(initialDelayMs = 1_000L, maxDelayMs = 4_000L)
        assertTrue(retry.canAttempt(0L))

        retry.recordFailure(0L)
        assertFalse(retry.canAttempt(999L))
        assertTrue(retry.canAttempt(1_000L))

        retry.recordFailure(1_000L)
        assertFalse(retry.canAttempt(2_999L))
        assertTrue(retry.canAttempt(3_000L))

        retry.recordFailure(3_000L)
        assertFalse(retry.canAttempt(6_999L))
        assertTrue(retry.canAttempt(7_000L))

        retry.recordFailure(7_000L)
        assertFalse(retry.canAttempt(10_999L))
        assertTrue(retry.canAttempt(11_000L))
    }

    @Test fun successfulStartClearsBackoff() {
        val retry = MapStartRetry(initialDelayMs = 1_000L)
        retry.recordFailure(100L)
        assertFalse(retry.canAttempt(200L))
        retry.recordSuccess()
        assertTrue(retry.canAttempt(200L))

        retry.recordFailure(200L)
        assertFalse(retry.canAttempt(1_199L))
        assertTrue(retry.canAttempt(1_200L))
    }
}
