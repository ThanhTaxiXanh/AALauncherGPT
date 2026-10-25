package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedRoadTurnTrackerTest {
    private val metre = 111_320.0
    private fun geometry(
        id: Long, aEast: Double, aNorth: Double,
        bEast: Double, bNorth: Double,
        startId: Long, endId: Long, oneway: Int = 0, level: Int = 0
    ) = SpeedWayGeometry(id, level, doubleArrayOf(
        aNorth / metre, aEast / metre,
        bNorth / metre, bEast / metre
    ), longArrayOf(startId, endId), oneway)

    private val east = SpeedWayMatch(0, 10L, true)
    private val north = SpeedWayMatch(1, 20L, true)
    private fun junction(): OsmRoadGraph = OsmRoadGraph(listOf(
        geometry(10, -100.0, 0.0, 0.0, 0.0, 1, 2),
        geometry(20, 0.0, 0.0, 0.0, 100.0, 2, 3)
    ))

    private fun see(
        tracker: ObservedRoadTurnTracker, graph: OsmRoadGraph,
        m: SpeedWayMatch, e: Double, n: Double, timeMs: Long,
        heading: Double = if (m == east) 90.0 else 0.0
    ) = tracker.observe(m, n / metre, e / metre, heading, true, 3.0, timeMs, graph)

    @Test fun confirmsInitialRoadAfterTwoDifferentGpsFixes() {
        val g = junction()
        val t = ObservedRoadTurnTracker()
        assertNull(see(t, g, east, -60.0, 0.0, 1_000))
        assertNull(see(t, g, east, -60.0, 0.0, 1_000)) // repeated UI refresh
        assertEquals(east, see(t, g, east, -45.0, 0.0, 2_000))
        assertEquals(east, see(t, g, east, -30.0, 0.0, 3_000))
    }

    @Test fun observedTurnRequiresRealMovementAlongNextWay() {
        val g = junction()
        val t = ObservedRoadTurnTracker()
        see(t, g, east, -60.0, 0.0, 1_000)
        see(t, g, east, -30.0, 0.0, 2_000)
        assertNull(see(t, g, north, 0.0, 2.0, 3_000))
        assertNull(see(t, g, north, 0.0, 2.0, 3_000))
        assertEquals(north, see(t, g, north, 0.0, 18.0, 4_000))
    }

    @Test fun oneFalseGpsWaySwitchNeverConfirmsTurn() {
        val g = junction()
        val t = ObservedRoadTurnTracker()
        see(t, g, east, -60.0, 0.0, 1_000)
        see(t, g, east, -40.0, 0.0, 2_000)
        assertNull(see(t, g, north, 0.0, 2.0, 3_000))
        assertEquals(east, see(t, g, east, -20.0, 0.0, 4_000))
        assertNull(see(t, g, north, 0.0, 7.0, 5_000))
    }

    @Test fun unsupportedOneWayHeadingCannotBeConfirmed() {
        val road = geometry(10, -100.0, 0.0, 0.0, 0.0, 1, 2, oneway = 1)
        val g = OsmRoadGraph(listOf(road))
        val tracker = ObservedRoadTurnTracker()
        val reverse = SpeedWayMatch(0, 10L, false)
        assertNull(see(tracker, g, reverse, -60.0, 0.0, 1_000))
        assertNull(see(tracker, g, reverse, -45.0, 0.0, 2_000))
    }

    @Test fun disconnectedWayRequiresIndependentReacquisition() {
        val roads = listOf(
            geometry(10, -100.0, 0.0, 0.0, 0.0, 1, 2),
            geometry(20, 0.0, 0.0, 0.0, 100.0, 99, 3)
        )
        val g = OsmRoadGraph(roads)
        val t = ObservedRoadTurnTracker()
        see(t, g, east, -70.0, 0.0, 1_000)
        see(t, g, east, -50.0, 0.0, 2_000)
        assertNull(see(t, g, north, 0.0, 1.0, 3_000))
        assertNull(see(t, g, north, 0.0, 13.0, 4_000))
        assertNull(see(t, g, north, 0.0, 22.0, 5_000))
        assertEquals(north, see(t, g, north, 0.0, 35.0, 6_000))
    }

    @Test fun missingGpsOrLongGapClearsCandidateHistory() {
        val g = junction()
        val t = ObservedRoadTurnTracker()
        see(t, g, east, -70.0, 0.0, 1_000)
        assertNull(see(t, g, east, -50.0, 0.0, 12_000))
        assertNull(t.observe(east, 0.0, 0.0, 90.0, true, 30.0, 13_000, g))
        assertNull(see(t, g, east, -40.0, 0.0, 14_000))
        assertEquals(east, see(t, g, east, -25.0, 0.0, 15_000))
    }

    @Test fun repeatedGpsTimestampDoesNotConfirmOnLoopRefreshes() {
        val g = junction()
        val t = ObservedRoadTurnTracker()
        for (i in 0 until 25) {
            assertNull(see(t, g, east, -70.0, 0.0, 1234))
        }
        assertEquals(east, see(t, g, east, -55.0, 0.0, 2_234))
    }

    @Test fun differentLayerAndWrongDirectionPreventGraphTransition() {
        val start = geometry(10, -100.0, 0.0, 0.0, 0.0, 1, 2)
        val bridge = geometry(20, 0.0, 0.0, 0.0, 100.0, 2, 3, level = 1)
        val wrongWay = geometry(20, 0.0, 0.0, 0.0, 100.0, 2, 3, oneway = -1)
        val from = SpeedWayMatch(0, 10, true)
        val to = SpeedWayMatch(1, 20, true)
        assertEquals(false, OsmRoadGraph(listOf(start, bridge)).observedTransition(
            from, to, -30.0 / metre, 0.0, 5.0 / metre, 0.0))
        assertEquals(false, OsmRoadGraph(listOf(start, wrongWay)).observedTransition(
            from, to, 0.0, -30.0 / metre, 5.0 / metre, 0.0))
    }

    @Test fun doesNotConfirmWhenVehicleStationary() {
        val t = ObservedRoadTurnTracker()
        val g = junction()
        assertNull(t.observe(east, 0.0, -30.0 / metre, 90.0,
            false, 3.0, 1_000, g))
        assertNull(see(t, g, east, -25.0, 0.0, 2_000))
    }
}
