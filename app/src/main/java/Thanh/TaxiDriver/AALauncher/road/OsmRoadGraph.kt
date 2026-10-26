package Thanh.TaxiDriver.AALauncher.road



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
internal class OsmRoadGraph(
    private val roads: List<SpeedWayGeometry>,
    private val restrictions: OsmTurnRestrictions = OsmTurnRestrictions()
) {
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
    private data class Direction(val way: Int, val forward: Boolean)
    private data class Edge(
        val key: Key, val from: Long, val to: Long, val level: Int,
        val lengthM: Double, val bearing: Double
    )
    private val edges = HashMap<Key, Edge>()
    private val outgoing = HashMap<Long, MutableList<Edge>>()
    private val nodeCoordinates = HashMap<Long, Pair<Double, Double>>()
    private val conflicted = HashSet<Long>()
    private val trackedWays = HashSet<Int>()
    private val directions = HashSet<Direction>()
    // Built once, replacing full graph scans on every GPS-confirmed junction.
    private val arriving = HashMap<Direction, MutableSet<Long>>()
    private val departing = HashMap<Direction, MutableSet<Long>>()
    private val maximumSegments = 24_000
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

    /** Constant-time directional edge existence lookup for GPS updates. */
    fun allowsTravel(match: SpeedWayMatch): Boolean =
        match.wayIndex in roads.indices &&
            roads[match.wayIndex].wayId == match.wayId &&
            Direction(match.wayIndex, match.forward) in directions

    /** Deterministic counters for large synthetic graph regression tests. */
    internal val segmentCount: Int get() = edges.size
    internal val indexedWayCount: Int get() = trackedWays.size

    /**
     * Validate an *observed* turn from one way to another. This does not
     * authorize a future turn; available OSM via-node restrictions are checked.
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
        val entering = arriving[Direction(before.wayIndex, before.forward)].orEmpty()
        val exiting = departing[Direction(after.wayIndex, after.forward)].orEmpty()
        if (entering.isEmpty() || exiting.isEmpty()) return false
        // Probe the smaller index: cost scales with only the two matching ways.
        val candidateNodes = if (entering.size <= exiting.size) entering else exiting
        val otherNodes = if (entering.size <= exiting.size) exiting else entering
        return candidateNodes.any { node ->
            if (node !in otherNodes) return@any false
            if (node in conflicted) false
            else {
                val loc = nodeCoordinates[node]
                loc != null &&
                    meters(beforeLat, beforeLon, loc.first, loc.second) <= nearJunctionM &&
                    meters(afterLat, afterLon, loc.first, loc.second) <= nearJunctionM &&
                    restrictions.decision(before.wayId, node, after.wayId) ==
                        TurnDecision.NO_KNOWN_RESTRICTION
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
            // Do not allocate a filtered list at every graph hop.
            // All exits must be checked; never invent a straight route at a fork.
            var next: Edge? = null
            var fork = false
            for (possible in outgoing[edge.to].orEmpty()) {
                if (possible.key.way == edge.key.way &&
                    possible.key.segment == edge.key.segment &&
                    possible.key.forward != edge.key.forward) continue
                if (next != null) { fork = true; break }
                next = possible
            }
            if (fork) break
            val continuation = next ?: break
            if (continuation.level != edge.level ||
                continuation.from in conflicted ||
                continuation.key in seen ||
                restrictions.decision(
                    roads[edge.key.way].wayId, edge.to, roads[continuation.key.way].wayId
                ) != TurnDecision.NO_KNOWN_RESTRICTION) break
            val maxTurn = if (continuation.key.way == edge.key.way) 120.0 else 55.0
            if (angle(edge.bearing, continuation.bearing) > maxTurn) break
            edge = continuation
            startFraction = if (edge.key.forward) 0.0 else 1.0
        }
        return path
    }

    private fun add(edge: Edge) {
        edges[edge.key] = edge
        outgoing.getOrPut(edge.from) { ArrayList() }.add(edge)
        val direction = Direction(edge.key.way, edge.key.forward)
        directions.add(direction)
        arriving.getOrPut(direction) { HashSet() }.add(edge.to)
        departing.getOrPut(direction) { HashSet() }.add(edge.from)
    }

    private fun meters(a: Double, b: Double, c: Double, d: Double): Double =
        hypot((c - a) * 111_320.0,
            (d - b) * 111_320.0 * cos(Math.toRadians((a + c) / 2.0)))

    private fun norm(v: Double): Double = (v % 360.0 + 360.0) % 360.0
    private fun angle(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)
}
