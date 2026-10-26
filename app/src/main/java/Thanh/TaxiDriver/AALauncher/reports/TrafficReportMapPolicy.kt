package Thanh.TaxiDriver.AALauncher.reports



import kotlin.math.cos
import kotlin.math.hypot

/** Personal note markers are never authoritative signs or speed warnings. */
internal object TrafficReportMapPolicy {
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_RADIUS_M = 2_000.0
    const val MAX_MARKERS = 40

    fun visible(
        reports: List<TrafficReport>, enabled: Boolean,
        lat: Double, lon: Double, nowMs: Long
    ): List<TrafficReport> {
        if (!enabled || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return emptyList()
        return reports.asSequence().mapNotNull { report ->
            val point = report.location ?: return@mapNotNull null
            val age = nowMs - report.createdAtMs
            // User-reported physical signs persist; traffic incidents expire.
            val permanentSign = report.kind.name.startsWith("SIGN_")
            if (age < 0 || (!permanentSign && age > MAX_AGE_MS))
                return@mapNotNull null
            val north = (point.lat - lat) * 111_320.0
            val east = (point.lon - lon) * 111_320.0 *
                cos(Math.toRadians((lat + point.lat) / 2.0))
            val distance = hypot(east, north)
            if (!distance.isFinite() || distance > MAX_RADIUS_M) null
                else distance to report
        }.sortedBy { it.first }.take(MAX_MARKERS).map { it.second }.toList()
    }
}
