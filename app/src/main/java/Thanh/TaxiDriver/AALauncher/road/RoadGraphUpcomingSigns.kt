package Thanh.TaxiDriver.AALauncher.road

import Thanh.TaxiDriver.AALauncher.signs.SignItem
import Thanh.TaxiDriver.AALauncher.signs.UpcomingSign

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Along-road warning lookup for the unique, non-branching graph prefix.
 *
 * No guessed route choice at intersections, no offline alerts and no
 * geometry-based transfer between unrelated OSM node IDs.
 */
internal object RoadGraphUpcomingSigns {
    private const val MAX_DISTANCE_M = 350.0
    private const val SIGN_OFFSET_M = 12.0

    fun upcoming(
        lat: Double, lon: Double, heading: Double, moving: Boolean,
        accuracyM: Double, match: SpeedWayMatch,
        roads: List<SpeedWayGeometry>, signs: List<SignItem>,
        graph: OsmRoadGraph, index: RoadSpatialIndex? = null
    ): List<UpcomingSign> {
        if (!moving || !heading.isFinite() || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM !in 0.0..25.0 ||
            match.wayIndex !in roads.indices ||
            roads[match.wayIndex].wayId != match.wayId) return emptyList()
        val start = roads[match.wayIndex]
        val car = projectToWay(lat, lon, start) ?: return emptyList()
        val lateralGate = min(25.0, max(8.0, accuracyM * 1.5 + 2.0))
        if (car.distM > lateralGate) return emptyList()
        val carDirection = if (match.forward) car.bearing else norm(car.bearing + 180.0)
        if (difference(carDirection, heading) > 45.0) return emptyList()
        val legs = graph.trace(match.wayIndex, car.segment, match.forward, car.fraction)
        if (legs.isEmpty()) return emptyList()

        val results = ArrayList<UpcomingSign>()
        for (sign in index?.signsNear(lat, lon, MAX_DISTANCE_M + 30.0) ?: signs) {
            if (!sign.lat.isFinite() || !sign.lon.isFinite() ||
                sign.lat !in -90.0..90.0 || sign.lon !in -180.0..180.0 ||
                pointDistance(lat, lon, sign.lat, sign.lon) > MAX_DISTANCE_M + 30.0) continue
            var distance: Double? = null
            for (leg in legs) {
                val geometry = roads[leg.wayIndex]
                val projected = projectSegment(sign.lat, sign.lon, geometry, leg.segmentIndex) ?: continue
                if (projected.distM > SIGN_OFFSET_M) continue
                val pointId = sign.osmNodeId
                if (pointId != null) {
                    val nodeAtBeginning = geometry.nodeIds.getOrNull(leg.segmentIndex)
                    val nodeAtEnd = geometry.nodeIds.getOrNull(leg.segmentIndex + 1)
                    // Only the matched edge may establish exact OSM membership.
                    if (pointId != nodeAtBeginning && pointId != nodeAtEnd &&
                        (index?.waysAtNode(pointId)?.isNotEmpty()
                            ?: roads.any { other -> other.nodeIds.contains(pointId) })) continue
                    // A shared node without facing or forward/backward tags
                    // does not prove which road/approach the sign applies to.
                    if ((pointId == nodeAtBeginning || pointId == nodeAtEnd) &&
                        sign.facing == null && sign.wayDirection == null &&
                        (index?.waysAtNode(pointId)?.size
                            ?: roads.count { it.nodeIds.contains(pointId) }) > 1) continue
                }
                if (sign.wayDirection != null &&
                    sign.wayDirection != (if (leg.forward) 1 else -1)) continue
                val frac = projected.fraction
                val onPartialLeg = if (leg.forward) frac >= leg.fromFraction - 0.001
                    else frac <= leg.fromFraction + 0.001
                if (!onPartialLeg) continue
                val dist = leg.distanceAt(frac)
                if (dist < 10.0 || dist > MAX_DISTANCE_M) continue
                // Geometry-only sign nodes must not have equally plausible
                // associations with other ways (parallel/crossing geometry).
                if (pointId == null ||
                    (index?.waysAtNode(pointId)?.isEmpty()
                        ?: roads.none { it.nodeIds.contains(pointId) })) {
                    val near = index?.segmentsNear(
                        sign.lat, sign.lon, SIGN_OFFSET_M + 5.0
                    )
                    val ambiguous = if (near != null) near.any { ref ->
                        if (ref.wayIndex == leg.wayIndex) false
                        else {
                            val p = projectSegment(
                                sign.lat, sign.lon, roads[ref.wayIndex], ref.segmentIndex
                            )
                            p != null && p.distM <= SIGN_OFFSET_M &&
                                p.distM <= projected.distM + 5.0
                        }
                    } else roads.withIndex().any { (idx, other) ->
                        if (idx == leg.wayIndex) false
                        else (0 until other.points.size / 2 - 1).any { j ->
                            val p = projectSegment(sign.lat, sign.lon, other, j)
                            p != null && p.distM <= SIGN_OFFSET_M &&
                                p.distM <= projected.distM + 5.0
                        }
                    }
                    if (ambiguous) continue
                }
                val roadBearing = if (leg.forward) projected.bearing
                    else norm(projected.bearing + 180.0)
                if (sign.facing != null &&
                    difference(norm(sign.facing + 180.0), roadBearing) > 65.0) continue
                if (sign.facing == null && sign.wayDirection == null &&
                    sign.type != "signals" && sign.type != "bump") continue
                if (distance == null || dist < distance) {
                    distance = dist
                }
            }
            val ahead = distance ?: continue
            if (results.any { previous ->
                    (sign.osmNodeId != null && previous.item.osmNodeId == sign.osmNodeId) ||
                        (previous.item.type == sign.type && previous.item.value == sign.value &&
                            pointDistance(previous.item.lat, previous.item.lon, sign.lat, sign.lon) < 5.0)
                }) continue
            results.add(UpcomingSign(sign, ahead.roundToInt()))
        }
        return results.sortedBy { it.distM }.take(4)
    }

    private data class Projection(
        val segment: Int, val fraction: Double, val distM: Double, val bearing: Double
    )

    private fun projectToWay(lat: Double, lon: Double, way: SpeedWayGeometry): Projection? {
        var best: Projection? = null
        for (i in 0 until way.points.size / 2 - 1) {
            val p = projectSegment(lat, lon, way, i) ?: continue
            if (best == null || p.distM < best.distM) best = p
        }
        return best
    }

    private fun projectSegment(lat: Double, lon: Double, way: SpeedWayGeometry, i: Int): Projection? {
        val p = way.points
        if (i < 0 || i * 2 + 3 >= p.size) return null
        val aLat = p[i * 2]; val aLon = p[i * 2 + 1]
        val bLat = p[i * 2 + 2]; val bLon = p[i * 2 + 3]
        val east = (bLon - aLon) * 111_320.0 * cos(Math.toRadians((aLat + bLat) / 2.0))
        val north = (bLat - aLat) * 111_320.0
        val square = east * east + north * north
        if (square < 0.25 || !square.isFinite()) return null
        val x = (lon - aLon) * 111_320.0 * cos(Math.toRadians((aLat + lat) / 2.0))
        val y = (lat - aLat) * 111_320.0
        val t = ((east * x + north * y) / square).coerceIn(0.0, 1.0)
        val dist = hypot(x - east * t, y - north * t)
        return Projection(i, t, dist, norm(Math.toDegrees(atan2(east, north))))
    }

    private fun pointDistance(a: Double, b: Double, c: Double, d: Double): Double =
        hypot((c - a) * 111_320.0,
            (d - b) * 111_320.0 * cos(Math.toRadians((a + c) / 2.0)))

    private fun norm(v: Double): Double = (v % 360.0 + 360.0) % 360.0
    private fun difference(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
}
