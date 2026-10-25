package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All coordinates are synthetic; 1 degree is approximated as 111320 m. */
class OsmRoadGraphTest {
    private val metres = 111_320.0

    private fun way(id: Long, east: DoubleArray, north: DoubleArray, nodes: LongArray,
                    oneWay: Int = 0, level: Int = 0): SpeedWayGeometry =
        SpeedWayGeometry(id, level,
            DoubleArray(east.size * 2) { index ->
                if (index % 2 == 0) north[index / 2] / metres else east[index / 2] / metres
            }, nodes, oneWay)

    private fun basic(): List<SpeedWayGeometry> = listOf(
        way(1, doubleArrayOf(-100.0, 0.0), doubleArrayOf(0.0, 0.0), longArrayOf(1, 2)),
        way(2, doubleArrayOf(0.0, 150.0), doubleArrayOf(0.0, 0.0), longArrayOf(2, 3))
    )

    @Test fun connectsTwoDistinctWaysBySharedNodeId() {
        val g = OsmRoadGraph(basic())
        assertTrue(g.containsWay(0))
        val path = g.trace(0, 0, true, 0.5)
        assertEquals(2, path.size)
        assertEquals(0, path[0].wayIndex)
        assertEquals(1, path[1].wayIndex)
        assertEquals(50.0, path[1].offsetM, 0.1)
    }

    @Test fun refusesGeometricallyTouchingButUnconnectedWays() {
        val roads = basic().toMutableList()
        roads[1] = way(2, doubleArrayOf(0.0, 150.0), doubleArrayOf(0.0, 0.0), longArrayOf(999, 3))
        assertEquals(1, OsmRoadGraph(roads).trace(0, 0, true, 0.5).size)
    }

    @Test fun refusesToChooseABranchAtThreeWayJunction() {
        val roads = basic() + way(3, doubleArrayOf(0.0, 0.0),
            doubleArrayOf(0.0, 150.0), longArrayOf(2, 4))
        assertEquals(1, OsmRoadGraph(roads).trace(0, 0, true, 0.5).size)
    }

    @Test fun connectsToReverseGeometryWhenAllowed() {
        val roads = listOf(
            basic()[0],
            way(2, doubleArrayOf(150.0, 0.0),
                doubleArrayOf(0.0, 0.0), longArrayOf(3, 2))
        )
        val path = OsmRoadGraph(roads).trace(0, 0, true, 0.5)
        assertEquals(2, path.size)
        assertFalse(path[1].forward)
    }

    @Test fun obeysOneWayAndReverseOneWay() {
        val forwardOnly = listOf(
            basic()[0], way(2, doubleArrayOf(150.0, 0.0),
                doubleArrayOf(0.0, 0.0), longArrayOf(3, 2), oneWay = 1)
        )
        assertEquals(1, OsmRoadGraph(forwardOnly).trace(0, 0, true, 0.5).size)
        assertEquals(2, OsmRoadGraph(listOf(
            basic()[0], way(2, doubleArrayOf(150.0, 0.0),
                doubleArrayOf(0.0, 0.0), longArrayOf(3, 2), oneWay = -1)
        )).trace(0, 0, true, 0.5).size)
    }

    @Test fun stackedRoadsAndDisplacedNodesDoNotConnect() {
        val stacked = listOf(basic()[0],
            way(2, doubleArrayOf(0.0, 150.0), doubleArrayOf(0.0, 0.0),
                longArrayOf(2, 3), level = 1))
        assertEquals(1, OsmRoadGraph(stacked).trace(0, 0, true, 0.5).size)
        val bad = listOf(basic()[0],
            way(2, doubleArrayOf(12.0, 150.0), doubleArrayOf(0.0, 0.0), longArrayOf(2, 3)))
        assertEquals(1, OsmRoadGraph(bad).trace(0, 0, true, 0.5).size)
    }

    @Test fun traversesInternalNodesButStopsAtBranchInsideWay() {
        val through = way(1, doubleArrayOf(-100.0, 0.0, 100.0),
            doubleArrayOf(0.0, 0.0, 0.0), longArrayOf(1, 2, 3))
        val branch = way(2, doubleArrayOf(0.0, 0.0),
            doubleArrayOf(0.0, 50.0), longArrayOf(2, 4))
        assertEquals(2, OsmRoadGraph(listOf(through)).trace(0, 0, true, 0.5).size)
        assertEquals(1, OsmRoadGraph(listOf(through, branch)).trace(0, 0, true, 0.5).size)
    }

    @Test fun refusesLoopOrUnknownOneWay() {
        val bad = way(1, doubleArrayOf(-100.0, 0.0), doubleArrayOf(0.0, 0.0),
            longArrayOf(1, 2), oneWay = 2)
        assertFalse(OsmRoadGraph(listOf(bad)).containsWay(0))
        assertTrue(OsmRoadGraph(basic()).trace(0, 0, true, Double.NaN).isEmpty())
    }

    @Test fun unknownOnewayWithOsmNodesNeverFallsBackToUnrestrictedWarnings() {
        val road = way(1, doubleArrayOf(-100.0, 100.0),
            doubleArrayOf(0.0, 0.0), longArrayOf(1, 2), oneWay = 2)
        val sign = SignItem(0.0, 50.0 / metres, "speed", "50", "50", 50, 270.0)
        val warnings = RoadSignRouteMatcher.upcoming(
            0.0, 0.0, 90.0, true, 3.0,
            SpeedWayMatch(0, 1, true), listOf(road), listOf(sign),
            OsmRoadGraph(listOf(road))
        )
        assertTrue(warnings.isEmpty())
    }

    @Test fun graphAlongDistanceContinuesIntoNextWayWithoutStraightLineShortcut() {
        val roads = listOf(
            way(1, doubleArrayOf(-100.0, 0.0), doubleArrayOf(0.0, 0.0), longArrayOf(1, 2)),
            way(2, doubleArrayOf(0.0, 150.0), doubleArrayOf(0.0, 0.0), longArrayOf(2, 3))
        )
        val sign = SignItem(0.0, 100.0 / metres, "speed", "50", "50", 50, 270.0)
        val alerts = RoadGraphUpcomingSigns.upcoming(
            0.0, -50.0 / metres, 90.0, true, 3.0,
            SpeedWayMatch(0, 1, true), roads, listOf(sign), OsmRoadGraph(roads)
        )
        assertEquals(1, alerts.size)
        assertEquals(150, alerts[0].distM)
    }

    @Test fun signBeyondForkIsNeverShownWithoutPlannedRoute() {
        val roads = basic() + way(3, doubleArrayOf(0.0, 0.0),
            doubleArrayOf(0.0, 150.0), longArrayOf(2, 4))
        val sign = SignItem(0.0, 100.0 / metres, "speed", "50", "50", 50, 270.0)
        val alerts = RoadGraphUpcomingSigns.upcoming(
            0.0, -50.0 / metres, 90.0, true, 3.0,
            SpeedWayMatch(0, 1, true), roads, listOf(sign), OsmRoadGraph(roads)
        )
        assertTrue(alerts.isEmpty())
    }
}
