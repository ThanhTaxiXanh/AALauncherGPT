package Thanh.TaxiDriver.AALauncher.gps



import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max

/** Geometry from the MapLibre transportation layer, not a lane-level position. */
internal data class RoadSegment(
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val level: Int = 0
)

internal data class RoadSnapMatch(
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val distanceM: Double
)

/**
 * Conservative, GPS-accuracy-aware road candidate matcher.
 *
 * This is not routing or lane-level localization. It deliberately returns null
 * when nearby parallel/crossing/stacked roads cannot be distinguished reliably.
 */
internal class RoadSnapMatcher {
    private data class Candidate(
        val segment: RoadSegment,
        val x: Double,
        val y: Double,
        val distanceM: Double,
        val bearing: Double,
        val score: Double
    )

    private var previous: RoadSegment? = null

    fun reset() {
        previous = null
    }

    fun match(
        lat: Double,
        lon: Double,
        heading: Double,
        accuracyM: Double,
        segments: List<RoadSegment>
    ): RoadSnapMatch? {
        // Without a trustworthy accuracy estimate, prefer the filtered GPS fix.
        if (!lat.isFinite() || !lon.isFinite() || !accuracyM.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            accuracyM < 0.0 || accuracyM > 25.0
        ) {
            reset()
            return null
        }

        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 111_320.0
        if (abs(kx) < 1.0) {
            reset()
            return null
        }
        val distanceGate = min(25.0, max(8.0, accuracyM * 1.5 + 2.0))
        val movingHeading = heading.isFinite()
        val candidates = ArrayList<Candidate>()

        for (segment in segments) {
            val ax = (segment.startLon - lon) * kx
            val ay = (segment.startLat - lat) * ky
            val bx = (segment.endLon - lon) * kx
            val by = (segment.endLat - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 0.01) continue

            val fraction = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + fraction * dx
            val y = ay + fraction * dy
            val distance = hypot(x, y)
            if (!distance.isFinite() || distance > distanceGate) continue

            val bearing = normalize(Math.toDegrees(atan2(dx, dy)))
            val headingError = if (movingHeading) undirectedDifference(bearing, heading) else 0.0
            if (movingHeading && headingError > 45.0) continue

            val continuityBonus = if (segment == previous) 5.0 else 0.0
            val score = distance + (if (movingHeading) 0.25 * headingError else 0.0) - continuityBonus
            candidates.add(Candidate(segment, x, y, distance, bearing, score))
        }

        val best = candidates.minByOrNull { it.score } ?: run {
            reset()
            return null
        }

        // Close competitors on different corridors or road levels are unsafe.
        // Adjacent pieces and duplicate tiles on the same axis are not rivals.
        for (other in candidates) {
            if (other === best) continue
            val separation = hypot(other.x - best.x, other.y - best.y)
            val differentLevel = other.segment.level != best.segment.level
            val crossingWithoutHeading = !movingHeading &&
                undirectedDifference(other.bearing, best.bearing) > 30.0
            val competingScore = other.score - best.score <= 4.0
            val stackedRoads = differentLevel &&
                abs(other.distanceM - best.distanceM) <= 3.0
            if (stackedRoads || (competingScore &&
                    (separation >= 5.0 || differentLevel || crossingWithoutHeading))
            ) {
                reset()
                return null
            }
        }

        previous = best.segment
        val orientedHeading = if (movingHeading) {
            val forward = if (angularDifference(best.bearing, heading) > 90.0)
                best.bearing + 180.0 else best.bearing
            normalize(forward)
        } else {
            Double.NaN
        }
        return RoadSnapMatch(
            lat + best.y / ky,
            lon + best.x / kx,
            orientedHeading,
            best.distanceM
        )
    }

    private fun normalize(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

    private fun angularDifference(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)

    private fun undirectedDifference(a: Double, b: Double): Double {
        val d = angularDifference(a, b)
        return min(d, 180.0 - d)
    }
}
