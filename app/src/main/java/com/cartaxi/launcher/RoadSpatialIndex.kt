package com.cartaxi.launcher

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable local metre-grid index for fresh Overpass ways and sign nodes.
 *
 * The grid is only used for compact, localized road sets. Unreliable input,
 * large spans or excessive memory cause null queries (full-scan fallback);
 * never risk excluding a competing road to save CPU time.
 *
 * Caller still computes exact geometry distances and GPS accuracy gates.
 */
internal class RoadSpatialIndex(
    private val roads: List<SpeedWayGeometry>,
    private val signs: List<SignItem> = emptyList()
) {
    internal data class Segment(val wayIndex: Int, val segmentIndex: Int)
    private data class Cell(val x: Int, val y: Int)

    private val stepM = 64.0
    private val originLat: Double
    private val originLon: Double
    private val longitudeScale: Double
    private val roadCells = HashMap<Cell, MutableList<Segment>>()
    private val signCells = HashMap<Cell, MutableList<Int>>()
    private val nodeWayMembership = HashMap<Long, MutableSet<Int>>()
    private var usable = true
    private var entries = 0
    private val maxEntries = 240_000
    private val maxCellSpan = 128

    init {
        val first = roads.asSequence().mapNotNull { r ->
            if (r.points.size >= 4) r.points[0] to r.points[1] else null
        }.firstOrNull() ?: signs.firstOrNull()?.let { it.lat to it.lon } ?: (0.0 to 0.0)
        originLat = first.first
        originLon = first.second
        longitudeScale = 111_320.0 * cos(Math.toRadians(originLat))
        if (!valid(originLat, originLon) || abs(originLat) > 65.0 ||
            !longitudeScale.isFinite() || longitudeScale < 40_000.0 ||
            abs(originLon) > 179.0
        ) usable = false

        if (usable) {
            for ((wayIndex, way) in roads.withIndex()) {
                if (!usable) break
                val p = way.points
                for (node in 0 until p.size / 2) {
                    val id = way.nodeIds.getOrNull(node)
                    if (id != null && id > 0L)
                        nodeWayMembership.getOrPut(id) { HashSet() }.add(wayIndex)
                }
                for (segment in 0 until p.size / 2 - 1) {
                    val ax = coordinates(p[segment * 2], p[segment * 2 + 1])
                    val bx = coordinates(p[segment * 2 + 2], p[segment * 2 + 3])
                    if (ax == null || bx == null) { usable = false; break }
                    val minX = floor(min(ax.first, bx.first) / stepM).toInt()
                    val maxX = floor(max(ax.first, bx.first) / stepM).toInt()
                    val minY = floor(min(ax.second, bx.second) / stepM).toInt()
                    val maxY = floor(max(ax.second, bx.second) / stepM).toInt()
                    if (maxX - minX > maxCellSpan || maxY - minY > maxCellSpan ||
                        entries + (maxX - minX + 1) * (maxY - minY + 1) > maxEntries
                    ) { usable = false; break }
                    val ref = Segment(wayIndex, segment)
                    for (x in minX..maxX) for (y in minY..maxY) {
                        roadCells.getOrPut(Cell(x, y)) { ArrayList() }.add(ref)
                        entries++
                    }
                }
            }
            if (usable) for ((i, sign) in signs.withIndex()) {
                val xy = coordinates(sign.lat, sign.lon)
                if (xy == null || ++entries > maxEntries) { usable = false; break }
                val cell = Cell(floor(xy.first / stepM).toInt(),
                    floor(xy.second / stepM).toInt())
                signCells.getOrPut(cell) { ArrayList() }.add(i)
            }
        }
        if (!usable) {
            roadCells.clear()
            signCells.clear()
            nodeWayMembership.clear()
        }
    }

    internal val indexed: Boolean get() = usable
    internal val segmentCount: Int get() = if (usable) roadCells.values.sumOf { it.size } else 0

    /** Returns null if the index cannot guarantee safe coverage. */
    fun segmentsNear(lat: Double, lon: Double, radiusM: Double): List<Segment>? {
        val xy = coordinates(lat, lon) ?: return null
        if (!radiusM.isFinite() || radiusM < 0.0) return null
        val cells = covering(xy.first, xy.second, radiusM + 16.0) ?: return null
        val found = LinkedHashSet<Segment>()
        for (c in cells) roadCells[c]?.let { found.addAll(it) }
        // Preserve original full-scan order for score and ambiguity decisions.
        return found.sortedWith(compareBy<Segment> { it.wayIndex }.thenBy { it.segmentIndex })
    }

    fun signsNear(lat: Double, lon: Double, radiusM: Double): List<SignItem>? {
        val xy = coordinates(lat, lon) ?: return null
        if (!radiusM.isFinite() || radiusM < 0.0) return null
        val cells = covering(xy.first, xy.second, radiusM + 16.0) ?: return null
        val ids = HashSet<Int>()
        for (c in cells) signCells[c]?.let { ids.addAll(it) }
        return ids.sorted().map { signs[it] }
    }

    /** Null means indexed membership is unavailable; caller scans all ways. */
    fun waysAtNode(nodeId: Long): Set<Int>? =
        if (usable) nodeWayMembership[nodeId].orEmpty() else null

    private fun coordinates(lat: Double, lon: Double): Pair<Double, Double>? {
        if (!usable || !valid(lat, lon)) return null
        val x = (lon - originLon) * longitudeScale
        val y = (lat - originLat) * 111_320.0
        // Error bound for this local tangent plane. Beyond 3 km, full scan.
        if (!x.isFinite() || !y.isFinite() ||
            abs(x) > 3_000.0 || abs(y) > 3_000.0) return null
        return x to y
    }

    private fun covering(x: Double, y: Double, radius: Double): List<Cell>? {
        val minX = floor((x - radius) / stepM).toInt()
        val maxX = floor((x + radius) / stepM).toInt()
        val minY = floor((y - radius) / stepM).toInt()
        val maxY = floor((y + radius) / stepM).toInt()
        if (maxX - minX > maxCellSpan || maxY - minY > maxCellSpan) return null
        return buildList {
            for (cx in minX..maxX) for (cy in minY..maxY) add(Cell(cx, cy))
        }
    }

    private fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
}
