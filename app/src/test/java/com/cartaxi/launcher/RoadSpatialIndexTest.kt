package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The indexed result must be identical to the conservative full-scan policy.
 * Dense synthetic city grids are deterministic, not a substitute for GPS logs.
 */
class RoadSpatialIndexTest {
    private val metres = 111_320.0
    private val originLat = 21.0
    private val originLon = 105.0
    private val eastScale = metres * kotlin.math.cos(Math.toRadians(originLat))

    private fun road(
        id: Long, e0: Double, n0: Double, e1: Double, n1: Double,
        level: Int = 0, nodes: LongArray = longArrayOf()
    ) = SpeedWayGeometry(
        id, level, doubleArrayOf(
            originLat + n0 / metres, originLon + e0 / eastScale,
            originLat + n1 / metres, originLon + e1 / eastScale
        ), nodes
    )
    private fun position(e: Double, n: Double) =
        (originLat + n / metres) to (originLon + e / eastScale)

    @Test fun denseGridHasFarFewerCandidatesThanBruteForce() {
        val roads = ArrayList<SpeedWayGeometry>()
        for (row in 0 until 70) for (col in 0 until 70) {
            val x = col * 24.0
            val y = row * 24.0
            roads.add(road(1_000L + roads.size, x, y, x + 16.0, y))
        }
        val index = RoadSpatialIndex(roads)
        assertTrue(index.indexed)
        assertEquals(4900, roads.size)
        val location = position(850.0, 850.0)
        val nearby = index.segmentsNear(location.first, location.second, 25.0)
        assertNotNull(nearby)
        assertTrue("dense local search should avoid a full scan", nearby!!.size < 100)
        val a = SpeedWayMatcher()
        val b = SpeedWayMatcher()
        for (row in 2 until 65 step 11) for (col in 2 until 65 step 11) {
            val fix = position(col * 24.0 + 8.0, row * 24.0)
            val baseline = a.match(fix.first, fix.second, 90.0, true, 3.0, roads)
            val optimized = b.match(fix.first, fix.second, 90.0, true, 3.0, roads, index)
            assertEquals("match row=$row col=$col", baseline, optimized)
            assertNotNull(optimized)
        }
    }

    @Test fun parallelCrossingAndStackedRoadsRemainAmbiguous() {
        val roads = listOf(
            road(11L, -120.0, 0.0, 120.0, 0.0),
            road(12L, -120.0, 7.0, 120.0, 7.0),
            road(13L, 0.0, -120.0, 0.0, 120.0),
            road(14L, -120.0, 0.0, 120.0, 0.0, level = 1)
        )
        val index = RoadSpatialIndex(roads)
        assertTrue(index.indexed)
        for (fix in listOf(position(0.0, 0.0),position(0.0, 3.0),
                position(50.0, 0.0),position(50.0, 6.0))) {
            assertEquals(
                SpeedWayMatcher().match(fix.first, fix.second, 90.0, true, 4.0, roads),
                SpeedWayMatcher().match(fix.first, fix.second, 90.0, true, 4.0, roads, index)
            )
        }
    }

    @Test fun signDistanceAndOtherRoadRejectionAgreeWithFullScan() {
        val main = road(11L, -200.0, 0.0, 200.0, 0.0, nodes = longArrayOf(1, 2))
        val parallel = road(12L, -200.0, 9.0, 200.0, 9.0, nodes = longArrayOf(3, 4))
        val signPos = position(100.0, 0.0)
        val neighborPos = position(100.0, 9.0)
        val good = SignItem(signPos.first, signPos.second,
            "speed", "40", "40", 40, facing = 270.0)
        val offRoad = SignItem(neighborPos.first, neighborPos.second,
            "speed", "60", "60", 60, facing = 270.0, osmNodeId = 4L)
        val roads = listOf(main, parallel)
        val signs = listOf(good, offRoad)
        val index = RoadSpatialIndex(roads, signs)
        val graph = OsmRoadGraph(roads)
        val car = position(0.0, 0.0)
        val match = SpeedWayMatch(0, 11L, true)
        val normal = RoadSignRouteMatcher.upcoming(
            car.first, car.second, 90.0, true, 3.0, match, roads, signs, graph
        )
        val fast = RoadSignRouteMatcher.upcoming(
            car.first, car.second, 90.0, true, 3.0, match, roads, signs, graph, index
        )
        assertEquals(normal, fast)
        assertEquals(1, normal.size) // nine-metre separation is not a score tie
        assertEquals(40, normal.single().item.value)
        assertEquals(setOf(1), index.waysAtNode(4L))
    }

    @Test fun indexedSignWarningsOnUniqueRoadMatchFullScan() {
        val main = road(11L, -200.0, 0.0, 200.0, 0.0,
            nodes = longArrayOf(1, 2))
        val signPos = position(100.0, 0.0)
        val sign = SignItem(signPos.first, signPos.second,
            "speed", "50", "50", 50, facing = 270.0)
        val roads = listOf(main)
        val signs = listOf(sign)
        val index = RoadSpatialIndex(roads, signs)
        val origin = position(0.0, 0.0)
        val match = SpeedWayMatch(0, 11L, true)
        val graph = OsmRoadGraph(roads)
        val baseline = RoadSignRouteMatcher.upcoming(origin.first, origin.second,
            90.0, true, 3.0, match, roads, signs, graph)
        val optimized = RoadSignRouteMatcher.upcoming(origin.first, origin.second,
            90.0, true, 3.0, match, roads, signs, graph, index)
        assertEquals(baseline, optimized)
        assertEquals(1, optimized.size)
        assertTrue(optimized.single().distM in 99..101)
    }

    @Test fun unsupportedRegionFallsBackToExhaustiveMatching() {
        val roads = listOf(
            road(1L, -50.0, 0.0, 50.0, 0.0),
            road(2L, 30_000.0, 0.0, 30_050.0, 0.0)
        )
        val index = RoadSpatialIndex(roads)
        assertFalse(index.indexed)
        val near = position(0.0, 0.0)
        assertNull(index.segmentsNear(near.first, near.second, 25.0))
        assertEquals(
            SpeedWayMatcher().match(near.first, near.second, 90.0, true, 3.0, roads),
            SpeedWayMatcher().match(near.first, near.second, 90.0, true, 3.0, roads, index)
        )
    }

    @Test fun indexFallsBackOutsideCompactCoverageWithoutDroppingRoads() {
        val first = road(11, -100.0, 0.0, 100.0, 0.0)
        val second = road(12, 4000.0, 0.0, 4100.0, 0.0)
        val roads = listOf(first, second)
        val index = RoadSpatialIndex(roads)
        assertFalse(index.indexed)
        assertNull(index.segmentsNear(originLat, originLon, 25.0))
    }

    @Test fun roadNodeMembershipAndMultipleCellBoundaries() {
        val first = road(11L, -250.0, -64.0, 250.0, -64.0, nodes = longArrayOf(1, 9))
        val second = road(12L, -64.0, -250.0, -64.0, 250.0, nodes = longArrayOf(3, 9))
        val index = RoadSpatialIndex(listOf(first, second))
        assertEquals(setOf(0,1), index.waysAtNode(9L))
        for (point in listOf(position(-64.0, -64.0),
                position(63.999, -64.0),position(-64.001, -64.0))) {
            assertEquals(
                SpeedWayMatcher().match(point.first, point.second, Double.NaN,
                    false, 3.0, listOf(first, second)),
                SpeedWayMatcher().match(point.first, point.second, Double.NaN,
                    false, 3.0, listOf(first, second), index)
            )
        }
    }
}
