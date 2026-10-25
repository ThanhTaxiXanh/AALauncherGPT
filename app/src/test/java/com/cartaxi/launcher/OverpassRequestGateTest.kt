package com.cartaxi.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassRequestGateTest {
    @Test fun preventsInFlightAndNearbySuccessDuplicates() {
        val g = OverpassRequestGate()
        assertTrue(g.begin(21.0, 105.0, 1000L))
        assertFalse(g.begin(21.0, 105.0, 1001L))
        g.complete(true, 2000L)
        assertFalse(g.begin(21.001, 105.001, 32_000L))
        assertTrue(g.begin(21.0, 105.0, 902_001L))
    }

    @Test fun globalThrottleSuppressesRequestsAcrossDifferentRegions() {
        val g = OverpassRequestGate()
        assertTrue(g.begin(21.0, 105.0, 0L))
        g.complete(true, 100L)
        assertFalse(g.begin(21.01, 105.01, 29_999L))
        assertTrue(g.begin(21.01, 105.01, 30_000L))
    }

    @Test fun failedAreaRetriesWithExponentialBackoff() {
        val g = OverpassRequestGate()
        assertTrue(g.begin(21.0, 105.0, 0L))
        g.complete(false, 100L)
        assertFalse(g.begin(21.0, 105.0, 30_099L))
        assertTrue(g.begin(21.0, 105.0, 30_100L))
        g.complete(false, 30_200L)
        assertFalse(g.begin(21.0, 105.0, 60_200L))
        assertTrue(g.begin(21.0, 105.0, 90_200L))
        g.complete(true, 90_300L)
        assertFalse(g.begin(21.0, 105.0, 200_000L))
    }

    @Test fun nearbyFailuresCannotBeBypassedBySmallGpsDrift() {
        val g = OverpassRequestGate()
        assertTrue(g.begin(21.0, 105.0, 0L))
        g.complete(false, 100L)
        assertFalse(g.begin(21.0008, 105.0008, 29_000L))
    }

    @Test fun invalidPositionsAreNotQueried() {
        val g = OverpassRequestGate()
        assertFalse(g.begin(Double.NaN, 105.0, 0L))
        assertFalse(g.begin(95.0, 105.0, 0L))
        assertFalse(g.inFlight())
    }
}
