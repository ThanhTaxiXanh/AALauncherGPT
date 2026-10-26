package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.signs.OfflineWayTransitionGate
import org.junit.Assert.*
import org.junit.Test

class OfflineWayTransitionGateTest {
    @Test fun requiresThreeConsecutiveFixesForNewWay() {
        val gate = OfflineWayTransitionGate()
        assertTrue(gate.accept(1))
        assertFalse(gate.accept(2))
        assertFalse(gate.accept(2))
        assertTrue(gate.accept(2))
        assertTrue(gate.accept(2))
    }

    @Test fun intermittentRoadDoesNotTriggerTransition() {
        val gate = OfflineWayTransitionGate()
        assertTrue(gate.accept(1))
        assertFalse(gate.accept(2))
        assertTrue(gate.accept(1))
        assertFalse(gate.accept(2))
        assertFalse(gate.accept(2))
        assertTrue(gate.accept(2))
    }

    @Test fun resetDropsPreviousWay() {
        val gate = OfflineWayTransitionGate()
        assertTrue(gate.accept(1))
        assertFalse(gate.accept(2))
        gate.reset()
        assertTrue(gate.accept(2))
    }
}
