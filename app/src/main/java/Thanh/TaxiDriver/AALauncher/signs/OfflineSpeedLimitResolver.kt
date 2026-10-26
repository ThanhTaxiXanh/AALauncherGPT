package Thanh.TaxiDriver.AALauncher.signs

import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatcher

import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative offline fallback for the bundled OSM speed-way database.
 *
 * The bundle contains only ways with explicit speed-related tags, so this
 * resolver applies a stricter GPS/name gate than the live Overpass matcher.
 * It fails closed near ambiguity instead of carrying a neighboring road's
 * speed limit onto the current road.
 */
internal class OfflineSpeedLimitResolver {
    private val matcher = SpeedWayMatcher()

    fun reset() {
        matcher.reset()
    }

    fun state(
        lat: Double,
        lon: Double,
        heading: Double,
        moving: Boolean,
        accuracyM: Double,
        gpsFixMs: Long,
        nowElapsedMs: Long,
        wallTimeMs: Long,
        streetName: String,
        roads: List<BundledSpeedRoad>
    ): SignsState {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM < 0.0 || accuracyM > 12.0 ||
            nowElapsedMs - gpsFixMs !in 0L..4_000L || roads.isEmpty()) {
            matcher.reset()
            return SignsState(null, false, emptyList(),
                "OSM offline chưa có đoạn tốc độ đủ tin cậy")
        }

        val geometries = roads.map { it.geometry }
        val match = matcher.match(
            lat, lon, heading, moving, accuracyM, geometries
        ) ?: return SignsState(null, false, emptyList(),
            "OSM offline chưa khớp chắc chắn đoạn đường")

        val road = roads[match.wayIndex]
        val exactDistance = distanceToGeometry(lat, lon, road.geometry)
        val nameMatches = roadNamesMatch(streetName, road.name)
        val strictDistance = if (nameMatches) {
            min(20.0, max(8.0, accuracyM * 1.25))
        } else {
            min(5.0, max(3.0, accuracyM * 0.55))
        }
        if (!exactDistance.isFinite() || exactDistance > strictDistance) {
            matcher.reset()
            return SignsState(null, false, emptyList(),
                "OSM offline: vị trí chưa đủ chắc chắn")
        }

        // If both sources know a road name they must agree. OpenFreeMap and the
        // offline bundle are both OSM-derived, so a mismatch is a useful
        // fail-closed signal for nearby parallel/service roads.
        if (streetName.isNotBlank() && road.name.isNotBlank() && !nameMatches) {
            matcher.reset()
            return SignsState(null, false, emptyList(),
                "OSM offline: tên đường không khớp")
        }

        val explicit = when {
            !moving || !heading.isFinite() -> {
                if (road.forward != null || road.backward != null ||
                    road.forwardConditional != null || road.backwardConditional != null
                ) null else ConditionalSpeedLimit.resolve(
                    road.all, road.allConditional, wallTimeMs
                )
            }
            match.forward -> ConditionalSpeedLimit.resolve(
                road.forward ?: road.all,
                road.forwardConditional ?: road.allConditional,
                wallTimeMs
            )
            else -> ConditionalSpeedLimit.resolve(
                road.backward ?: road.all,
                road.backwardConditional ?: road.allConditional,
                wallTimeMs
            )
        }

        if (explicit == null) {
            return SignsState(null, false, emptyList(),
                "OSM offline: giới hạn chưa xác định / có điều kiện")
        }
        return SignsState(
            limit = explicit,
            estimated = false,
            upcoming = emptyList(),
            status = "Giới hạn từ OSM offline (tham khảo)",
            roadWayId = match.wayId
        )
    }

    private fun distanceToGeometry(
        lat: Double, lon: Double, geometry: SpeedWayGeometry
    ): Double {
        val points = geometry.points
        if (points.size < 4) return Double.POSITIVE_INFINITY
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 111_320.0
        var best = Double.POSITIVE_INFINITY
        var i = 0
        while (i + 3 < points.size) {
            val ax = (points[i + 1] - lon) * kx
            val ay = (points[i] - lat) * ky
            val bx = (points[i + 3] - lon) * kx
            val by = (points[i + 2] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0.01) {
                (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            } else 0.0
            best = min(best, hypot(ax + t * dx, ay + t * dy))
            i += 2
        }
        return best
    }

    private fun roadNamesMatch(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val a = normalizeRoadName(left)
        val b = normalizeRoadName(right)
        return a == b || (a.length >= 6 && b.contains(a)) ||
            (b.length >= 6 && a.contains(b))
    }

    private fun normalizeRoadName(value: String): String {
        val folded = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('đ', 'd')
        return folded.replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
