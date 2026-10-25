package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.OsmRoadGraph
import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic 5,000-way graph stress test. No brittle wall-clock threshold;
 * graph counters and repeated indexed GPS lookups guard against regressions.
 */
class OsmRoadGraphScaleTest {
    private val scale = 111_320.0

    @Test fun buildsFiveThousandWaysAndPreservesTopologyThroughIndexedLookups() {
        val count = 5_000
        val roads = (0 until count).map { i ->
            val west = i * 20.0 / scale
            val east = (i + 1) * 20.0 / scale
            SpeedWayGeometry(
                500_000L + i, 0,
                doubleArrayOf(0.0, west, 0.0, east),
                longArrayOf(800_000L + i, 800_001L + i),
                oneway = 1
            )
        }
        val start = System.nanoTime()
        val graph = OsmRoadGraph(roads)
        val constructionMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000, graph.indexedWayCount)
        assertEquals(5_000, graph.segmentCount)
        for (i in 0 until count step 7) {
            val from = SpeedWayMatch(i, roads[i].wayId, true)
            assertTrue(graph.allowsTravel(from))
            assertFalse(graph.allowsTravel(from.copy(forward = false)))
            if (i + 1 < count) {
                val to = SpeedWayMatch(i + 1, roads[i + 1].wayId, true)
                val nodeLon = (i + 1) * 20.0 / scale
                assertTrue(graph.observedTransition(
                    from, to,
                    0.0, nodeLon - 5.0 / scale,
                    0.0, nodeLon + 5.0 / scale
                ))
            }
        }
        val trace = graph.trace(0, 0, true, 0.5)
        assertTrue(trace.isNotEmpty())
        assertEquals(350.0, trace.last().offsetM + trace.last().lengthM, 15.0)
        // Informational CI log; a strict timing requirement requires devices
        // and warm JVM profiling rather than shared GitHub runners.
        println("OSM road graph: ways=$count, edges=${graph.segmentCount}, buildMs=$constructionMs")
    }
}
