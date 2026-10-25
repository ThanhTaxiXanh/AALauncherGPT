package com.cartaxi.launcher

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conservative along-way warnings; not a navigation router. Only signs
 * demonstrably attached to the matched OSM way are eligible.
 *
 * When a sign or vehicle is ambiguous between road branches, or direction
 * cannot be verified, returns no alert rather than a straight-line guess.
 */
internal object RoadSignRouteMatcher {
    private const val MAX_LOOKAHEAD_M = 350.0
    private const val MIN_LOOKAHEAD_M = 10.0
    private const val MAX_SIGN_OFFSET_M = 12.0
    private const val MAX_CAR_HEADING_DEGREES = 45.0
    private const val MAX_SIGN_FACING_DEGREES = 65.0

    private data class Projection(
        val alongM: Double,
        val distanceM: Double,
        val segment: Int,
        val forwardBearing: Double
    )

    private class RouteLine(private val geometry: SpeedWayGeometry, lat0: Double, lon0: Double) {
        val count = geometry.points.size / 2
        val x = DoubleArray(count)
        val y = DoubleArray(count)
        val cumulative = DoubleArray(count)
        init {
            val kx = 111_320.0 * cos(Math.toRadians(lat0))
            for (i in 0 until count) {
                x[i] = (geometry.points[2 * i + 1] - lon0) * kx
                y[i] = (geometry.points[2 * i] - lat0) * 111_320.0
                if (i > 0) cumulative[i] = cumulative[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            }
        }
        fun project(lat: Double, lon: Double, lat0: Double, lon0: Double): Projection? {
            val px = (lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))
            val py = (lat - lat0) * 111_320.0
            return projectPoint(px, py)
        }

        fun projectPoint(px: Double, py: Double): Projection? {
            var best: Projection? = null
            var alternate: Projection? = null
            for (i in 0 until count - 1) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val lengthSquared = dx * dx + dy * dy
                if (!lengthSquared.isFinite() || lengthSquared < 0.01) continue
                val t = ((px - x[i]) * dx + (py - y[i]) * dy) / lengthSquared
                val fraction = t.coerceIn(0.0, 1.0)
                val distance = hypot(px - x[i] - fraction * dx, py - y[i] - fraction * dy)
                val along = cumulative[i] + fraction * hypot(dx, dy)
                val b = norm(Math.toDegrees(atan2(dx, dy)))
                val next = Projection(along, distance, i, b)
                if (best == null || distance < best.distanceM) {
                    alternate = best
                    best = next
                } else if (alternate == null || distance < alternate.distanceM) {
                    alternate = next
                }
            }
            // Self-intersecting U-turn/loop roads can project to two different
            // along-way positions at the same geographic point.
            val winner = best ?: return null
            if (alternate != null && alternate.distanceM - winner.distanceM <= 4.0 &&
                abs(alternate.alongM - winner.alongM) > 25.0) return null
            return winner
        }

        fun node(index: Int): Projection? {
            if (index !in 0 until count || count < 2) return null
            val segment = if (index == count - 1) index - 1 else index
            val dx = x[segment + 1] - x[segment]
            val dy = y[segment + 1] - y[segment]
            if (hypot(dx, dy) < 0.1) return null
            return Projection(cumulative[index], 0.0, segment,
                norm(Math.toDegrees(atan2(dx, dy))))
        }

        fun nodeDistance(index: Int, lat: Double, lon: Double, lat0: Double, lon0: Double): Double {
            val px = (lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))
            val py = (lat - lat0) * 111_320.0
            return hypot(px - x[index], py - y[index])
        }
    }

    fun upcoming(
        lat: Double, lon: Double, heading: Double, moving: Boolean,
        accuracyM: Double, match: SpeedWayMatch,
        roads: List<SpeedWayGeometry>, signs: List<SignItem>,
        graph: OsmRoadGraph? = null
    ): List<UpcomingSign> {
        // A graph with verified OSM node IDs supports multiple connected ways.
        // Geometry lacking node IDs retains the pre-existing single-way fallback.
        if (graph?.containsWay(match.wayIndex) == true) {
            return RoadGraphUpcomingSigns.upcoming(
                lat, lon, heading, moving, accuracyM, match, roads, signs, graph
            )
        }
        if (!moving || !heading.isFinite() || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM < 0.0 || accuracyM > 25.0 ||
            match.wayIndex !in roads.indices) return emptyList()
        val road = roads[match.wayIndex]
        if (road.wayId != match.wayId || road.points.size < 4) return emptyList()

        val route = RouteLine(road, lat, lon)
        val rivals by lazy { roads.filter { it.wayId != road.wayId }.map { RouteLine(it, lat, lon) } }
        val car = route.projectPoint(0.0, 0.0) ?: return emptyList()
        if (car.distanceM > min(25.0, max(8.0, accuracyM * 1.5 + 2.0))) return emptyList()
        val carBearing = if (match.forward) car.forwardBearing else norm(car.forwardBearing + 180.0)
        if (angleDiff(carBearing, heading) > MAX_CAR_HEADING_DEGREES) return emptyList()

        val candidate = ArrayList<UpcomingSign>()
        for (sign in signs) {
            if (!sign.lat.isFinite() || !sign.lon.isFinite()) continue
            // Route distance can only exceed straight-line distance; this
            // cheap bound avoids projecting remote OSM nodes.
            val crow = localDistance(lat, lon, sign.lat, sign.lon)
            if (crow > MAX_LOOKAHEAD_M + 30.0) continue
            val nodeId = sign.osmNodeId
            val idx = if (nodeId != null && road.nodeIds.size == route.count)
                road.nodeIds.indexOf(nodeId) else -1
            // If the sign belongs to a different OSM way, do not project it
            // onto the current way just because two centerlines are close.
            if (nodeId != null && idx < 0 &&
                roads.any { other -> other.nodeIds.contains(nodeId) }) continue
            val sharedNode = idx >= 0 && nodeId != null && roads.any { other ->
                other.wayId != road.wayId && other.nodeIds.contains(nodeId)
            }
            if (sharedNode && sign.facing == null) continue

            val projection = if (idx >= 0) {
                if (route.nodeDistance(idx, sign.lat, sign.lon, lat, lon) > MAX_SIGN_OFFSET_M) continue
                route.node(idx)
            } else route.project(sign.lat, sign.lon, lat, lon)
            if (projection == null || projection.distanceM > MAX_SIGN_OFFSET_M) continue
            if (idx < 0) {
                // Geometry-only attachment must be unique across OSM ways.
                var ambiguous = false
                for (other in rivals) {
                    val otherProjection = other.project(sign.lat, sign.lon, lat, lon) ?: continue
                    if (otherProjection.distanceM <= MAX_SIGN_OFFSET_M &&
                        otherProjection.distanceM <= projection.distanceM + 5.0) {
                        ambiguous = true
                        break
                    }
                }
                if (ambiguous) continue
            }
            val along = if (match.forward) projection.alongM - car.alongM
                else car.alongM - projection.alongM
            if (along < MIN_LOOKAHEAD_M || along > MAX_LOOKAHEAD_M) continue
            if (sign.wayDirection != null && sign.wayDirection != (if (match.forward) 1 else -1))
                continue
            val atSignBearing = if (match.forward) projection.forwardBearing
                else norm(projection.forwardBearing + 180.0)
            if (sign.facing != null) {
                // OSM direction is the facing of the sign; the driver approaches
                // approximately opposite that physical direction.
                if (angleDiff(norm(sign.facing + 180.0), atSignBearing) >
                    MAX_SIGN_FACING_DEGREES) continue
            } else if (sign.wayDirection == null &&
                sign.type != "signals" && sign.type != "bump") {
                // Most signs are directional: without a direction/side hint,
                // their legal applicability cannot be established.
                continue
            }
            val rounded = along.roundToInt().coerceAtLeast(1)
            // Deduplicate only the same sign ID (or almost identical sign
            // position/type/value), never simply signs of the same type.
            if (candidate.any { earlier ->
                    (sign.osmNodeId != null && earlier.item.osmNodeId == sign.osmNodeId) ||
                        (earlier.item.type == sign.type && earlier.item.value == sign.value &&
                            localDistance(earlier.item.lat, earlier.item.lon, sign.lat, sign.lon) < 5.0)
                }) continue
            candidate.add(UpcomingSign(sign, rounded))
        }
        return candidate.sortedBy { it.distM }.take(4)
    }

    private fun localDistance(a: Double, b: Double, c: Double, d: Double): Double {
        val x = (d - b) * 111_320.0 * cos(Math.toRadians((a + c) / 2.0))
        val y = (c - a) * 111_320.0
        return hypot(x, y)
    }
    private fun norm(v: Double): Double = (v % 360.0 + 360.0) % 360.0
    private fun angleDiff(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
}
