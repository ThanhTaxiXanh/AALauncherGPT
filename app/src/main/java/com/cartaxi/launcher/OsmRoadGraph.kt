package com.cartaxi.launcher

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Bounded directed OSM segment graph built once per fresh Overpass response.
 *
 * Node IDs, not coordinate proximity, determine connectivity. Each way is
 * divided at every OSM node, including nodes internal to an OSM way. An
 * unambiguous forward continuation may cross a way boundary, but the graph
 * stops at intersections with multiple legally traversable choices.
 *
 * This is NOT navigation routing and does not evaluate turn-restriction
 * relations or vehicle-specific access rules.
 */
internal class OsmRoadGraph(private val roads: List<SpeedWayGeometry>) {
    internal data class Leg(
        val wayIndex: Int,
        val segmentIndex: Int,
        val forward: Boolean,
        val fromFraction: Double,
        val toFraction: Double,
        val offsetM: Double,
        val lengthM: Double,
        val bearing: Double
    ) {
        fun distanceAt(fraction: Double): Double =
            offsetM + (if (forward) fraction - fromFraction else fromFraction - fraction) * lengthM
    }

    private data class Key(val way: Int, val segment: Int, val forward: Boolean)
    private data class Edge(
        val key: Key, val from: Long, val to: Long, val level: Int,
        val lengthM: Double, val bearing: Double
    )
    private val edges = HashMap<Key, Edge>()
    private val outgoing = HashMap<Long, MutableList<Edge>>()
    private val nodeCoordinates = HashMap<Long, Pair<Double, Double>>()
    private val conflicted = HashSet<Long>()
    private val trackedWays = HashSet<Int>()
    private val maximumSegments = 6000
    private val maxHops = 60

    init {
        var n = 0
        for ((wayIndex, road) in roads.withIndex()) {
            val nodes = road.nodeIds
            val points = road.points
            val count = points.size / 2
            if (nodes.size != count || count < 2 || road.oneway !in -1..1) continue
            for (i in nodes.indices) {
                val id = nodes[i]
                val lat = points[2 * i]
                val lon = points[2 * i + 1]
                if (id <= 0L || !lat.isFinite() || !lon.isFinite()) continue
                val old = nodeCoordinates.putIfAbsent(id, lat to lon)
                if (old != null && meters(old.first, old.second, lat, lon) > 4.0) conflicted.add(id)
            }
            for (i in 0 until count - 1) {
                if (n >= maximumSegments) break
                val a = nodes[i]
                val b = nodes[i + 1]
                if (a <= 0L || b <= 0L || a == b) continue
                val lat1 = points[i * 2]
                val lon1 = points[i * 2 + 1]
                val lat2 = points[i * 2 + 2]
                val lon2 = points[i * 2 + 3]
                if (!lat1.isFinite() || !lon1.isFinite() ||
                    !lat2.isFinite() || !lon2.isFinite()) continue
                val north = (lat2 - lat1) * 111_320.0
                val east = (lon2 - lon1) * 111_320.0 *
                    cos(Math.toRadians((lat1 + lat2) / 2.0))
                val length = hypot(north, east)
                if (!length.isFinite() || length < 0.5) continue
                val bearing = norm(Math.toDegrees(atan2(east, north)))
                if (road.oneway >= 0) add(Edge(Key(wayIndex, i, true), a, b, road.level, length, bearing))
                if (road.oneway <= 0) add(Edge(Key(wayIndex, i, false), b, a, road.level, length, norm(bearing + 180.0)))
                trackedWays.add(wayIndex)
                n++
            }
        }
    }

    fun containsWay(wayIndex: Int): Boolean = wayIndex in trackedWays

    /** A GPS way/direction must be actually drivable under OSM oneway tags. */
    fun allowsTravel(match: SpeedWayMatch): Boolean =
        match.wayIndex in roads.indices &&
            roads[match.wayIndex].wayId == match.wayId &&
            edges.keys.any { it.way == match.wayIndex && it.forward == match.forward }

    /**
     * Validate an *observed* turn from one way to another. This does not
     * authorize a future turn; OSM turn-restriction relations are not loaded.
     * Require the preceding directed segment to enter a shared OSM node and
     * the new directed segment to leave it at the same level.
     */
    fun observedTransition(
        before: SpeedWayMatch, after: SpeedWayMatch,
        beforeLat: Double, beforeLon: Double,
        afterLat: Double, afterLon: Double,
        nearJunctionM: Double = 85.0
    ): Boolean {
        if (before.wayIndex == after.wayIndex) return false
        if (!allowsTravel(before) || !allowsTravel(after) ||
            !beforeLat.isFinite() || !beforeLon.isFinite() ||
            !afterLat.isFinite() || !afterLon.isFinite() ||
            !nearJunctionM.isFinite() || nearJunctionM <= 0.0) return false
        val fromWay = roads[before.wayIndex]
        val toWay = roads[after.wayIndex]
        if (fromWay.level != toWay.level) return false
        val entering = edges.values.asSequence()
            .filter { it.key.way == before.wayIndex && it.key.forward == before.forward }
            .map { it.to }.toSet()
        val exiting = edges.values.asSequence()
            .filter { it.key.way == after.wayIndex && it.key.forward == after.forward }
            .map { it.from }.toSet()
        return (entering intersect exiting).any { node ->
            if (node in conflicted) false
            else {
                val loc = nodeCoordinates[node]
                loc != null &&
                    meters(beforeLat, beforeLon, loc.first, loc.second) <= nearJunctionM &&
                    meters(afterLat, afterLon, loc.first, loc.second) <= nearJunctionM
            }
        }
    }

    /**
     * Returns an ordered non-branching prefix, starting at the GPS projection
     * on a known segment. Later legs are full segments. Stops on ambiguous
     * turns, different layers, missing IDs, loops or excessive length.
     */
    fun trace(
        wayIndex: Int, segmentIndex: Int, forward: Boolean,
        fraction: Double, maxDistanceM: Double = 350.0
    ): List<Leg> {
        if (!fraction.isFinite() || fraction !in 0.0..1.0 ||
            !maxDistanceM.isFinite() || maxDistanceM <= 0.0) return emptyList()
        var edge = edges[Key(wayIndex, segmentIndex, forward)] ?: return emptyList()
        var startFraction = fraction
        var offset = 0.0
        val path = ArrayList<Leg>()
        val seen = HashSet<Key>()
        for (hop in 0 until maxHops) {
            if (!seen.add(edge.key)) break
            val endFraction = if (edge.key.forward) 1.0 else 0.0
            val remaining = abs(endFraction - startFraction) * edge.lengthM
            if (remaining < 0.001) break
            path.add(Leg(
                edge.key.way, edge.key.segment, edge.key.forward,
                startFraction, endFraction, offset, edge.lengthM, edge.bearing
            ))
            offset += remaining
            if (offset >= maxDistanceM || edge.to in conflicted) break
            val options = outgoing[edge.to].orEmpty().filter { next ->
                // Never make an implicit U-turn on the segment just traveled.
                !(next.key.way == edge.key.way &&
                    next.key.segment == edge.key.segment &&
                    next.key.forward != edge.key.forward)
            }
            // All possible forward exits are considered before the turn angle:
            // do not invent a straight route at an intersection with a turn.
            if (options.size != 1) break
            val next = options.single()
            if (next.level != edge.level ||
                next.from in conflicted ||
                next.key in seen) break
            val maxTurn = if (next.key.way == edge.key.way) 120.0 else 55.0
            if (angle(edge.bearing, next.bearing) > maxTurn) break
            edge = next
            startFraction = if (edge.key.forward) 0.0 else 1.0
        }
        return path
    }

    private fun add(edge: Edge) {
        edges[edge.key] = edge
        outgoing.getOrPut(edge.from) { ArrayList() }.add(edge)
    }

    private fun meters(a: Double, b: Double, c: Double, d: Double): Double =
        hypot((c - a) * 111_320.0,
            (d - b) * 111_320.0 * cos(Math.toRadians((a + c) / 2.0)))

    private fun norm(v: Double): Double = (v % 360.0 + 360.0) % 360.0
    private fun angle(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
}
