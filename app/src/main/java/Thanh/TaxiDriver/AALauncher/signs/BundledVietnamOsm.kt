package Thanh.TaxiDriver.AALauncher.signs

import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/**
 * A nearby drivable OSM way. Speed fields may all be null: those roads still
 * participate in GPS matching so a neighboring speed-tagged way cannot leak
 * its limit onto the vehicle's actual road.
 */
internal data class BundledRoadContext(
    val geometry: SpeedWayGeometry,
    val highway: String,
    val name: String,
    val all: Int?,
    val forward: Int?,
    val backward: Int?,
    val allConditional: String?,
    val forwardConditional: String?,
    val backwardConditional: String?,
    val hasSpeedTag: Boolean
)

/**
 * Separate read-only OSM dataset extracted from Geofabrik on the CI runner.
 * Installed from APK assets on first access, not downloaded on device.
 */
internal class BundledVietnamOsm(private val ctx: Context) {
    private val folder = File(ctx.filesDir, "osm_offline")
    private val data = File(folder, "vietnam_signs.sqlite")
    private val installedHash = File(folder, "vietnam_signs.sha256")
    private var database: SQLiteDatabase? = null

    @Synchronized private fun open(): SQLiteDatabase? {
        val wanted = try {
            ctx.assets.open("osm/vietnam_signs.sha256").bufferedReader().use { it.readText().trim() }
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        require(wanted.matches(Regex("[0-9a-f]{64}"))) { "OSM asset hash invalid" }
        if (installedHash.takeIf { it.isFile }?.readText() != wanted ||
            !data.isFile || data.length() == 0L) {
            database?.close()
            database = null
            folder.mkdirs()
            val pending = File(folder, "vietnam_signs.tmp")
            try {
                val dig = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                ctx.assets.open("osm/vietnam_signs.sqlite.gzbin").use { file ->
                    GZIPInputStream(file.buffered()).use { gz ->
                        FileOutputStream(pending).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val count = gz.read(buf)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= 800_000_000L) {
                                    "OSM bundle exceeds 800 MB unpacked"
                                }
                                out.write(buf, 0, count)
                                dig.update(buf, 0, count)
                            }
                            out.fd.sync()
                        }
                    }
                }
                require(bytes > 4096L && dig.digest().joinToString("") {
                    "%02x".format(it) } == wanted) { "OSM bundle verification failed" }
                require(pending.renameTo(data)) { "Could not install OSM bundle" }
                installedHash.writeText(wanted)
            } finally {
                pending.delete()
            }
        }
        if (database?.isOpen != true) {
            database = SQLiteDatabase.openDatabase(data.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY)
        }
        return database
    }

    @Synchronized fun nearby(lat: Double, lon: Double, radiusM: Double = 2000.0,
        limit: Int = 120): List<RegionalOsmStore.Marker> {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0) return emptyList()
        val db = open() ?: return emptyList()
        val dy = radiusM / 111320.0
        val dx = radiusM / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val out = ArrayList<RegionalOsmStore.Marker>()
        db.rawQuery(
            """SELECT id,region,lat,lon,type,label,speed_value,source,osm_id
               FROM markers WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
               LIMIT 500""",
            arrayOf((lat-dy).toString(),(lat+dy).toString(),
                (lon-dx).toString(),(lon+dx).toString())
        ).use { c ->
            while (c.moveToNext()) {
                val north = (c.getDouble(2)-lat)*111320.0
                val east = (c.getDouble(3)-lon)*111320.0*cos(Math.toRadians(lat))
                if (north*north+east*east <= radiusM*radiusM) {
                    out.add(RegionalOsmStore.Marker(c.getString(0),c.getString(1),
                        c.getDouble(2),c.getDouble(3),c.getString(4),c.getString(5),
                        c.getInt(6),c.getString(7),c.getString(8)))
                }
            }
        }
        return out.take(limit)
    }

    /**
     * V3 road context: includes supported motor roads even when maxspeed is
     * absent. R-Tree prefilters a small GPS neighborhood, then SpeedWayMatcher
     * decides which exact geometry (if any) the vehicle is on.
     */
    @Synchronized fun nearbyRoadContext(
        lat: Double, lon: Double, radiusM: Double = 120.0, limit: Int = 300
    ): List<BundledRoadContext> {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0 || radiusM !in 25.0..500.0 ||
            limit !in 1..400) return emptyList()
        val db = open() ?: return emptyList()
        val dy = radiusM / 111320.0
        val dx = radiusM / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val sql = """
            SELECT r.osm_way_id,r.highway,r.name,r.maxspeed,
                   r.forward_speed,r.backward_speed,r.conditional,
                   r.forward_conditional,r.backward_conditional,
                   r.has_speed_tag,r.oneway,r.level,r.geometry_json
            FROM road_context_rtree AS idx
            JOIN road_context AS r ON r.id=idx.id
            WHERE idx.min_lon <= ? AND idx.max_lon >= ?
              AND idx.min_lat <= ? AND idx.max_lat >= ?
            LIMIT ${limit.coerceAtMost(400)}
        """.trimIndent()
        val out = ArrayList<BundledRoadContext>()
        db.rawQuery(sql, arrayOf(
            (lon+dx).toString(), (lon-dx).toString(),
            (lat+dy).toString(), (lat-dy).toString()
        )).use { c ->
            while (c.moveToNext()) {
                val geometry = parseLineGeometry(
                    wayId = c.getLong(0),
                    level = c.getInt(11),
                    oneway = c.getInt(10),
                    raw = c.getString(12)
                ) ?: continue
                out.add(BundledRoadContext(
                    geometry = geometry,
                    highway = c.getString(1),
                    name = c.getString(2),
                    all = speedOrNull(c.getInt(3)),
                    forward = speedOrNull(c.getInt(4)),
                    backward = speedOrNull(c.getInt(5)),
                    allConditional = textOrNull(c.getString(6)),
                    forwardConditional = textOrNull(c.getString(7)),
                    backwardConditional = textOrNull(c.getString(8)),
                    hasSpeedTag = c.getInt(9) == 1
                ))
            }
        }
        return out
    }

    private fun speedOrNull(value: Int): Int? = value.takeIf { it in 5..160 }
    private fun textOrNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseLineGeometry(
        wayId: Long, level: Int, oneway: Int, raw: String
    ): SpeedWayGeometry? {
        val root = JSONObject(raw)
        if (root.optString("type") != "LineString") return null
        val coords = root.optJSONArray("coordinates") ?: return null
        if (coords.length() !in 2..5000) return null
        val points = DoubleArray(coords.length() * 2)
        for (i in 0 until coords.length()) {
            val pair = coords.optJSONArray(i) ?: return null
            if (pair.length() < 2) return null
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            points[i * 2] = lat
            points[i * 2 + 1] = lon
        }
        return SpeedWayGeometry(wayId, level, points, LongArray(0), oneway)
    }

    @Synchronized fun close() {
        database?.close()
        database = null
    }
}
