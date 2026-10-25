package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long, version-controlled GPS fixture (>900 independent fixes).
 * A fresh tracker is used for each direction, exactly as after a GPS reset.
 */
class LongGpsReplayTest {
    private val scale = 111_320.0
    private val count = 120
    private fun ways(): List<SpeedWayGeometry> = (0 until count).map { i ->
        SpeedWayGeometry(
            10_000L + i, 0,
            doubleArrayOf(0.0, (i * 100.0) / scale,
                0.0, ((i + 1) * 100.0) / scale),
            longArrayOf(50_000L + i, 50_001L + i)
        )
    }
    @Test fun replay960AcceptedFixesAcross240RoadTransitions() {
        val source = javaClass.getResourceAsStream("/gps_replay/long_two_way.csv")
        assertNotNull(source)
        val lines = source!!.bufferedReader().use { it.readLines() }
            .drop(1).filter { it.isNotBlank() }
        assertEquals(960, lines.size)
        val roads = ways()
        val graph = OsmRoadGraph(roads)
        assertEquals(count, graph.indexedWayCount)
        var lastRoute = ""
        var tracker = ObservedRoadTurnTracker()
        var matcher = SpeedWayMatcher()
        var confirmedCount = 0
        var previousTimestamp = 0L
        var previousWay = -1L
        var transitions = 0
        for ((number, line) in lines.withIndex()) {
            val c = line.split(',')
            assertEquals(7, c.size)
            val route = c[0]
            if (route != lastRoute) {
                tracker = ObservedRoadTurnTracker()
                matcher = SpeedWayMatcher()
                lastRoute = route
                previousTimestamp = 0
                previousWay = -1L
            }
            val timestamp = c[1].toLong()
            assertTrue("nonmonotonic at row $number", timestamp > previousTimestamp)
            previousTimestamp = timestamp
            val lon = c[2].toDouble() / scale
            val lat = c[3].toDouble() / scale
            val heading = c[4].toDouble()
            val expectedWay = c[5].toLong()
            val expectedConfirmed = c[6].takeIf { it != "-" }?.toLong()
            val found = matcher.match(lat, lon, heading, true, 3.0, roads)
            assertEquals("road mismatch row $number", expectedWay, found?.wayId)
            val confirmed = tracker.observe(
                found!!, lat, lon, heading, true, 3.0, timestamp, graph
            )
            assertEquals("replay confirmation row $number",
                expectedConfirmed, confirmed?.wayId)
            if (confirmed != null) confirmedCount++
            if (previousWay > 0L && previousWay != found.wayId) transitions++
            previousWay = found.wayId
            if (number % 37 == 0 && confirmed != null) {
                val segment = roads[confirmed.wayIndex]
                assertTrue(graph.allowsTravel(confirmed))
                val along = if (confirmed.forward) 0.5 else 0.5
                assertTrue(graph.trace(confirmed.wayIndex, 0, confirmed.forward, along)
                    .isNotEmpty())
                assertEquals(segment.wayId, confirmed.wayId)
            }
        }
        assertEquals(720, confirmedCount)
        assertEquals(238, transitions)
    }
}
