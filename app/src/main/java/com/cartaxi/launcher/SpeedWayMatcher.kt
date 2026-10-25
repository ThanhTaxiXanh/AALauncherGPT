package com.cartaxi.launcher

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** OpenStreetMap way geometry: alternating latitude, longitude pairs. */
internal class SpeedWayGeometry(
    val wayId: Long,
    val level: Int,
    val points: DoubleArray
)

/** Forward follows the OSM way node order (not a GPS lane claim). */
internal data class SpeedWayMatch(
    val wayIndex: Int,
    val wayId: Long,
    val forward: Boolean
)

/** Abstains rather than selecting speed limits for indistinguishable ways. */
internal class SpeedWayMatcher {
    private data class Candidate(
        val index: Int, val id: Long, val level: Int,
        val x: Double, val y: Double, val distance: Double,
        val forward: Boolean, val score: Double
    )
    private var previousWayId: Long? = null
    fun reset() { previousWayId = null }

    fun match(
        lat: Double, lon: Double, heading: Double,
        moving: Boolean, accuracyM: Double, roads: List<SpeedWayGeometry>
    ): SpeedWayMatch? {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM < 0.0 || accuracyM > 25.0
        ) { reset(); return null }
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 111_320.0
        if (abs(kx) < 1.0) { reset(); return null }
        val gate = min(25.0, max(8.0, accuracyM * 1.5 + 2.0))
        val hasHeading = moving && heading.isFinite()
        val candidates = ArrayList<Candidate>()
        for ((index, road) in roads.withIndex()) {
            if (road.wayId <= 0L) continue
            val pts = road.points
            for (i in 0 until pts.size / 2 - 1) {
                val ax = (pts[2 * i + 1] - lon) * kx
                val ay = (pts[2 * i] - lat) * ky
                val bx = (pts[2 * i + 3] - lon) * kx
                val by = (pts[2 * i + 2] - lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (!len2.isFinite() || len2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val x = ax + t * dx
                val y = ay + t * dy
                val distance = hypot(x, y)
                if (!distance.isFinite() || distance > gate) continue
                val bearing = normalize(Math.toDegrees(atan2(dx, dy)))
                val difference = if (hasHeading) angleDifference(bearing, heading) else 0.0
                val axisError = min(difference, 180.0 - difference)
                if (hasHeading && axisError > 45.0) continue
                val score = distance + if (hasHeading) axisError * 0.25 else 0.0
                candidates.add(Candidate(
                    index, road.wayId, road.level, x, y, distance,
                    !hasHeading || difference <= 90.0, score
                ))
            }
        }
        // Continuity is a minor tie-breaker, never an ambiguity override.
        val best = candidates.minWithOrNull(
            compareBy<Candidate> { it.score - (if (it.id == previousWayId) 1.0 else 0.0) }
                .thenBy { it.distance }
        ) ?: run { reset(); return null }
        for (other in candidates) {
            if (other.id == best.id && other.level == best.level) continue
            val separation = hypot(best.x - other.x, best.y - other.y)
            val stacked = best.level != other.level && separation < 6.0
            val plausibleCompetitor = abs(other.score - best.score) <= 5.0
            if (stacked || plausibleCompetitor) { reset(); return null }
        }
        previousWayId = best.id
        return SpeedWayMatch(best.index, best.id, best.forward)
    }
    private fun normalize(a: Double): Double = ((a % 360.0) + 360.0) % 360.0
    private fun angleDifference(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)
}
