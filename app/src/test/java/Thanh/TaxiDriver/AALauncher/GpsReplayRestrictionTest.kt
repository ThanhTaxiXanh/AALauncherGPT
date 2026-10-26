package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.ObservedRoadTurnTracker
import Thanh.TaxiDriver.AALauncher.road.OsmRoadGraph
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestriction
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestrictions
import Thanh.TaxiDriver.AALauncher.road.RoadSignRouteMatcher
import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatcher
import Thanh.TaxiDriver.AALauncher.signs.SignItem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Deterministic, file-backed GPS replay. Exercises actual SpeedWayMatcher,
 * ObservedRoadTurnTracker, OSM topology and upcoming sign projection together.
 * Fixture coordinates are local metres, converted to a small lat/lon area.
 */
class GpsReplayRestrictionTest {
    private val metresPerDegree = 111_320.0

    private data class Fix(
        val scenario: String,
        val timeMs: Long, val east: Double, val north: Double,
        val heading: Double, val accuracy: Double, val moving: Boolean,
        val expectedMatch: Long?, val expectedConfirmed: Long?, val expectedAlerts: Int
    )

    private fun parseExpected(s: String): Long? =
        if (s == "-") null else s.toLong()

    private fun fixture(): List<Fix> {
        val input = javaClass.getResourceAsStream("/gps_replay/turn_tracks.csv")
        assertNotNull("Missing GPS replay fixture in test resources", input)
        return input!!.bufferedReader().use { stream ->
            stream.lineSequence().drop(1)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val fields = line.trim().split(',')
                    require(fields.size == 10) { "Bad GPS fixture line: $line" }
                    Fix(
                        fields[0], fields[1].toLong(), fields[2].toDouble(),
                        fields[3].toDouble(), fields[4].toDouble(),
                        fields[5].toDouble(), fields[6].toBooleanStrict(),
                        parseExpected(fields[7]), parseExpected(fields[8]),
                        fields[9].toInt()
                    )
                }.toList()
        }
    }

    private fun way(
        id: Long, aEast: Double, aNorth: Double, bEast: Double, bNorth: Double,
        fromNode: Long, toNode: Long
    ) = SpeedWayGeometry(id, 0,
        doubleArrayOf(
            aNorth / metresPerDegree, aEast / metresPerDegree,
            bNorth / metresPerDegree, bEast / metresPerDegree
        ), longArrayOf(fromNode, toNode))

    @Test fun replayGpsTurnScenariosWithRestrictionsAndRoadRelativeWarnings() {
        val scenarios = fixture().groupBy { it.scenario }
        assertEquals(
            setOf("legal", "restricted", "jitter", "poor_gps", "disconnected"),
            scenarios.keys
        )
        for ((name, fixes) in scenarios) {
            val from = way(10, -100.0, 0.0, 0.0, 0.0, 1, 2)
            val toNode = if (name == "disconnected") 999L else 2L
            val to = way(20, 0.0, 0.0, 0.0, 130.0, toNode, 3)
            val roads = listOf(from, to)
            val restrictions = if (name == "restricted")
                OsmTurnRestrictions(listOf(
                    OsmTurnRestriction(111L, 10L, 20L, 2L, "no_left_turn")
                )) else OsmTurnRestrictions()
            val graph = OsmRoadGraph(roads, restrictions)
            val matcher = SpeedWayMatcher()
            val tracker = ObservedRoadTurnTracker()
            val signs = listOf(SignItem(
                100.0 / metresPerDegree, 0.0,
                "speed", "50", "Speed 50", 50, facing = 180.0,
                osmNodeId = null
            ))
            var prevTime = -1L
            for (fix in fixes) {
                val tag = "$name at ${fix.timeMs}"
                require(fix.timeMs >= prevTime) { "Fixture times must be monotonic" }
                prevTime = fix.timeMs
                val lat = fix.north / metresPerDegree
                val lon = fix.east / metresPerDegree
                val match = matcher.match(
                    lat, lon, fix.heading, fix.moving, fix.accuracy, roads
                )
                assertEquals("$tag: matched way", fix.expectedMatch, match?.wayId)
                val confirmed = if (match != null) tracker.observe(
                    match, lat, lon, fix.heading, fix.moving,
                    fix.accuracy, fix.timeMs, graph
                ) else {
                    tracker.reset()
                    null
                }
                assertEquals("$tag: confirmed way", fix.expectedConfirmed, confirmed?.wayId)
                val warnings = if (confirmed != null)
                    RoadSignRouteMatcher.upcoming(
                        lat, lon, fix.heading, fix.moving,
                        fix.accuracy, confirmed, roads, signs, graph
                    ) else emptyList()
                assertEquals("$tag: forward warning count",
                    fix.expectedAlerts, warnings.size)
                if (warnings.isNotEmpty()) {
                    // Exact distance from current fix to the north-100m sign.
                    assertEquals("$tag: road distance",
                        (100.0 - fix.north).toInt(), warnings.single().distM)
                }
            }
        }
    }
}
