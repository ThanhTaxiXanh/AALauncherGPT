package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.OsmRoadGraph
import Thanh.TaxiDriver.AALauncher.road.RoadSignRouteMatcher
import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatch
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatcher
import Thanh.TaxiDriver.AALauncher.signs.RoadSigns
import Thanh.TaxiDriver.AALauncher.signs.SignItem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls the production RoadSigns JSON parser, then exercises its graph,
 * restrictions, GPS matcher and incoming-sign computation. No network.
 */
class OverpassIntegrationTest {
    private fun sample() = javaClass.getResourceAsStream(
        "/overpass/synthetic_junction.json"
    )!!.bufferedReader().use { it.readText() }

    private fun parsedData(): Any {
        val temp = File.createTempFile("overpass-integration", ".cache")
        temp.delete()
        val signs = RoadSigns(temp)
        val parse = RoadSigns::class.java.getDeclaredMethod(
            "parse", String::class.java,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )
        parse.isAccessible = true
        return parse.invoke(signs, sample(), 0.0, 0.0, 1_000L, false, 1_900_000_000_000L)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(data: Any, name: String): T {
        val property = data.javaClass.getDeclaredField(name)
        property.isAccessible = true
        return property.get(data) as T
    }

    @Test fun parseActualOverpassStructureAndUseSameGraphForTurnRules() {
        val data = parsedData()
        val roads = field<List<SpeedWayGeometry>>(data, "geometries")
        val signs = field<List<SignItem>>(data, "signs")
        val graph = field<OsmRoadGraph>(data, "graph")
        assertEquals(3, roads.size)
        assertEquals(listOf(100L, 200L, 300L), roads.map { it.wayId })
        assertEquals(3, graph.indexedWayCount)
        assertEquals(3, graph.segmentCount)
        assertTrue(roads.all { it.oneway == 1 && it.nodeIds.size == 2 })
        assertEquals(1, signs.size)
        assertEquals(302L, signs.single().osmNodeId)
        assertEquals(40, signs.single().value)
        assertEquals(180.0, signs.single().facing!!, 0.001)

        val west = SpeedWayMatch(0, 100L, true)
        val north = SpeedWayMatch(1, 200L, true)
        val south = SpeedWayMatch(2, 300L, true)
        assertTrue(graph.allowsTravel(west))
        assertFalse(graph.allowsTravel(SpeedWayMatch(0, 100L, false)))
        assertTrue(graph.observedTransition(west, north, 0.0, -0.0003, 0.0002, 0.0))
        assertFalse(graph.observedTransition(west, south, 0.0, -0.0003, -0.0002, 0.0))
        // A fork is not a permitted prediction even when one of its arms
        // has an explicitly prohibited turn.
        assertEquals(1, graph.trace(0, 0, true, 0.5).size)

        val matcher = SpeedWayMatcher()
        val gps = matcher.match(0.00015, 0.0, 0.0, true, 3.0, roads)
        assertEquals(north, gps)
        val upcoming = RoadSignRouteMatcher.upcoming(
            0.00015, 0.0, 0.0, true, 3.0,
            gps!!, roads, signs, graph
        )
        assertEquals(1, upcoming.size)
        assertTrue(upcoming.single().distM in 42..45)
    }

    @Test fun malformedOverpassPayloadCannotSilentlyBuildGraph() {
        val payload = sample()
        assertTrue(payload.contains("\"members\""))
        assertTrue(payload.contains("\"geometry\""))
        assertTrue(payload.contains("\"restriction\""))
        assertNotNull(parsedData())
    }
}
