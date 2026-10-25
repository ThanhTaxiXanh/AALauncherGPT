package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SignCachePolicyTest {
    private val now = 1_900_000_000_000L
    private val hour = 3_600_000L

    @Test fun liveDataCanBeUsedWithinFifteenMinutes() {
        assertEquals(SignCacheUse.LIVE, SignCachePolicy.classify(
            false, 60_000L, now - 60_000L, now, 50.0))
        assertEquals(SignCacheUse.OFFLINE_HINT, SignCachePolicy.classify(
            false, 16 * 60_000L, now - 16 * 60_000L, now, 50.0))
    }

    @Test fun diskCacheNeverBecomesLiveEvenIfRecentlySaved() {
        assertEquals(SignCacheUse.OFFLINE_HINT, SignCachePolicy.classify(
            true, 0L, now - 10_000L, now, 50.0))
    }

    @Test fun expiredFutureOrOutOfRangeCacheIsNotDisplayed() {
        assertEquals(SignCacheUse.NONE, SignCachePolicy.classify(
            true, 0L, now - 8L * 24 * hour, now, 50.0))
        assertEquals(SignCacheUse.NONE, SignCachePolicy.classify(
            true, 0L, now + 1L, now, 50.0))
        assertEquals(SignCacheUse.NONE, SignCachePolicy.classify(
            true, 0L, now - 1000L, now, 901.0))
        assertEquals(SignCacheUse.NONE, SignCachePolicy.classify(
            true, 0L, now - 1000L, now, Double.NaN))
    }

    @Test fun rebootMonotonicClockCannotForgeLiveData() {
        assertEquals(SignCacheUse.OFFLINE_HINT, SignCachePolicy.classify(
            false, -100L, now - hour, now, 50.0))
    }
}
