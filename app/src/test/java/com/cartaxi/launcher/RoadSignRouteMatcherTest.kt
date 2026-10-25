package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSignRouteMatcherTest {
    private val meter = 111_320.0

    private fun road(id: Long, points: List<Pair<Double, Double>>, ids: LongArray = longArrayOf()) =
        SpeedWayGeometry(id, 0, points.flatMap { listOf(it.first / meter, it.second / meter) }.toDoubleArray(), ids)

    private fun sign(north: Double, east: Double, facing: Double? = null,
                     id: Long? = null, type: String = "speed",
                     direction: Int? = null, value: Int = 50) =
        SignItem(north / meter, east / meter, type, value.toString(), "Test", value,
            facing, id, direction)

    private fun alerts(road: SpeedWayGeometry, signList: List<SignItem>,
                       forward: Boolean = true, heading: Double = if (forward) 90.0 else 270.0,
                       other: List<SpeedWayGeometry> = emptyList()): List<UpcomingSign> =
        RoadSignRouteMatcher.upcoming(
            0.0, 0.0, heading, true, 3.0,
            SpeedWayMatch(0, road.wayId, forward), listOf(road) + other, signList
        )

    @Test fun curvedRoadDistanceIsLongerThanStraightLine() {
        // Drive east 100 m, then north 100 m; sign at far end.
        val path = road(11, listOf(0.0 to -50.0, 0.0 to 100.0, 100.0 to 100.0),
            longArrayOf(1, 2, 3))
        val warnings = alerts(path, listOf(sign(100.0, 100.0, 180.0, 3)))
        assertEquals(1, warnings.size)
        assertEquals(200, warnings[0].distM) // 100 east + 100 north
    }

    @Test fun signsBehindAreNeverUpcoming() {
        val path = road(11, listOf(0.0 to -200.0, 0.0 to 200.0),
            longArrayOf(1, 2))
        assertTrue(alerts(path, listOf(sign(0.0, -50.0, 270.0))).isEmpty())
    }

    @Test fun reverseTravelUsesReverseArcLengthAndOppositeFacing() {
        val path = road(11, listOf(0.0 to -200.0, 0.0 to 200.0))
        val warnings = alerts(path, listOf(sign(0.0, -90.0, 90.0)), forward = false)
        assertEquals(90, warnings.single().distM)
    }

    @Test fun parallelAdjacentWayCannotStealItsSign() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 200.0),
            longArrayOf(1, 2))
        val parallel = road(12, listOf(8.0 to -200.0, 8.0 to 200.0),
            longArrayOf(3, 4))
        assertTrue(alerts(main, listOf(sign(8.0, 100.0, 270.0)), other = listOf(parallel)).isEmpty())
    }

    @Test fun signAssignedToOtherWayByNodeIdIsRejected() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 200.0),
            longArrayOf(1, 2))
        val parallel = road(12, listOf(8.0 to -200.0, 8.0 to 200.0),
            longArrayOf(3, 9))
        assertTrue(alerts(main, listOf(sign(8.0, 200.0, 270.0, 9)), other = listOf(parallel)).isEmpty())
    }

    @Test fun sharedIntersectionNodeWithoutDirectionIsAmbiguous() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 100.0, 0.0 to 200.0),
            longArrayOf(1, 9, 2))
        val crossing = road(12, listOf(-50.0 to 100.0, 0.0 to 100.0, 50.0 to 100.0),
            longArrayOf(3, 9, 4))
        assertTrue(alerts(main, listOf(sign(0.0, 100.0, null, 9, "stop")), other = listOf(crossing)).isEmpty())
    }

    @Test fun incompatibleFacingOrWayDirectionIsSuppressed() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 200.0))
        assertTrue(alerts(main, listOf(sign(0.0, 100.0, 90.0))).isEmpty())
        assertTrue(alerts(main, listOf(sign(0.0, 100.0, null, null, direction = -1))).isEmpty())
        assertEquals(1, alerts(main, listOf(sign(0.0, 100.0, null, null, direction = 1))).size)
    }

    @Test fun cannotClaimDirectionalSignWithoutFacingOrTag() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 200.0))
        assertTrue(alerts(main, listOf(sign(0.0, 100.0))).isEmpty())
        assertEquals(1, alerts(main, listOf(sign(0.0, 100.0, null, null, type = "signals"))).size)
    }

    @Test fun noMatchBadGpsOrStoppedMeansNoAheadAlerts() {
        val main = road(11, listOf(0.0 to -200.0, 0.0 to 200.0))
        val sign = sign(0.0, 100.0, 270.0)
        assertTrue(RoadSignRouteMatcher.upcoming(0.0, 0.0, 90.0, false, 3.0,
            SpeedWayMatch(0, 11, true), listOf(main), listOf(sign)).isEmpty())
        assertTrue(RoadSignRouteMatcher.upcoming(0.0, 0.0, 90.0, true, 30.0,
            SpeedWayMatch(0, 11, true), listOf(main), listOf(sign)).isEmpty())
        assertTrue(RoadSignRouteMatcher.upcoming(0.0, 0.0, 90.0, true, 3.0,
            SpeedWayMatch(0, 12, true), listOf(main), listOf(sign)).isEmpty())
    }

    @Test fun distanceThresholdAndDuplicatesAreHandledWithoutFalseMerging() {
        val main = road(11, listOf(0.0 to -500.0, 0.0 to 500.0))
        val a = sign(0.0, 100.0, 270.0, 10)
        val b = sign(0.0, 102.0, 270.0, 10)
        val c = sign(0.0, 115.0, 270.0, 11)
        val far = sign(0.0, 390.0, 270.0, 12)
        val warnings = alerts(main, listOf(a, b, c, far))
        assertEquals(2, warnings.size)
        assertEquals(100, warnings[0].distM)
        assertEquals(115, warnings[1].distM)
    }
}
