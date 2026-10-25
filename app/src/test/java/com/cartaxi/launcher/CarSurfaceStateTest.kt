package com.cartaxi.launcher

import org.junit.Assert.*
import org.junit.Test

/** Simulates Android Auto host callbacks without hardware. */
class CarSurfaceStateTest {
    @Test fun noUpdatesBeforeHostScreenStartsAndProvidesSurface() {
        val state = CarSurfaceState()
        val surface = Any()
        assertFalse(state.canRun())
        assertTrue(state.onAvailable(surface, 1280, 720))
        assertFalse(state.canRun())
        state.onStart()
        assertTrue(state.canRun())
    }

    @Test fun screenStopSuspendsWithoutDestroyingOwnedSurface() {
        val state = CarSurfaceState()
        val token = Any()
        state.onStart()
        state.onAvailable(token, 800, 480)
        assertTrue(state.canRun())
        state.onStop()
        assertFalse(state.canRun())
        assertTrue(state.owns(token))
        state.onStart()
        assertTrue(state.canRun())
    }

    @Test fun oldSurfaceDestroyDoesNotCloseReplacement() {
        val state = CarSurfaceState()
        val first = Any()
        val second = Any()
        state.onStart()
        assertTrue(state.onAvailable(first, 1000, 600))
        assertTrue(state.onAvailable(second, 1200, 650))
        assertFalse(state.onDestroyed(first))
        assertTrue(state.owns(second))
        assertEquals(1200, state.width)
        assertTrue(state.canRun())
        assertTrue(state.onDestroyed(second))
        assertFalse(state.canRun())
        assertEquals(0, state.width)
    }

    @Test fun repeatedDestroyIsIdempotent() {
        val state = CarSurfaceState()
        val token = Any()
        state.onStart()
        state.onAvailable(token, 600, 400)
        assertTrue(state.onDestroyed(token))
        assertFalse(state.onDestroyed(token))
        assertFalse(state.canRun())
    }

    @Test fun screenDestroyInvalidatesCallbacksAndCannotRestart() {
        val state = CarSurfaceState()
        val token = Any()
        state.onStart()
        state.onAvailable(token, 900, 500)
        state.onDestroy()
        assertFalse(state.canRun())
        assertEquals(0, state.width)
        assertFalse(state.onAvailable(Any(), 900, 500))
        state.onStart()
        assertFalse(state.canRun())
    }

    @Test fun invalidSurfaceDimensionsDoNotReplaceValidSurface() {
        val state = CarSurfaceState()
        val token = Any()
        state.onStart()
        state.onAvailable(token, 640, 480)
        assertFalse(state.onAvailable(Any(), 0, 480))
        assertFalse(state.onAvailable(Any(), 8193, 720))
        assertFalse(state.onAvailable(null, 800, 480))
        assertTrue(state.owns(token))
        assertTrue(state.canRun())
    }

    @Test fun equalButNotIdenticalHostTokensAreDistinct() {
        val state = CarSurfaceState()
        val a = String(charArrayOf('s'))
        val b = String(charArrayOf('s'))
        assertNotSame(a, b)
        state.onStart()
        state.onAvailable(a, 700, 400)
        assertFalse(state.onDestroyed(b))
        assertTrue(state.owns(a))
    }

    @Test fun noGpsReadinessWithoutLiveSurface() {
        assertEquals(CarGpsState.SURFACE_WAIT,
            CarGpsPolicy.state(false, true, 100_000, 99_000))
    }

    @Test fun gpsPermissionMustBePresent() {
        assertEquals(CarGpsState.PERMISSION_REQUIRED,
            CarGpsPolicy.state(true, false, 100_000, 99_000))
    }

    @Test fun missingAndExpiredGpsAreNotReady() {
        assertEquals(CarGpsState.WAITING,
            CarGpsPolicy.state(true, true, 50_000, 0))
        assertEquals(CarGpsState.STALE,
            CarGpsPolicy.state(true, true, 55_001, 50_000))
        assertEquals(CarGpsState.STALE,
            CarGpsPolicy.state(true, true, 40_000, 50_000))
    }

    @Test fun onlyRecentGpsIsReady() {
        assertEquals(CarGpsState.READY,
            CarGpsPolicy.state(true, true, 55_000, 50_000))
        assertEquals(CarGpsState.READY,
            CarGpsPolicy.state(true, true, 50_200, 50_000))
    }
}
