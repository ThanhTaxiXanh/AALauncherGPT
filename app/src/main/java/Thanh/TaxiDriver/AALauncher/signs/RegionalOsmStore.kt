package Thanh.TaxiDriver.AALauncher.signs

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.cos

/**
 * Local OSM regional tile store. Distinct from unverified user submissions.
 *
 * Accepts bounded GeoJSON exports of real OSM Point features with explicit
 * osm_id, source and sign category. No guessed coordinates/official limits.
 * Import replaces only the named region transactionally and can be repeated.
 */
class RegionalOsmStore(context: Context) :
    SQLiteOpenHelper(context, "vietnam_osm_regions_v1.db", null, 1) {

    data class Marker(
        val id: String, val region: String, val lat: Double, val lon: Double,
        val type: String, val label: String, val value: Int,
        val source: String, val osmId: String
    )
    data class Region(val key: String, val count: Int, val updatedMs: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE regions (
            region TEXT PRIMARY KEY, updated_ms INTEGER NOT NULL,
            feature_count INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE markers (
            region TEXT NOT NULL, id TEXT NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL,
            type TEXT NOT NULL, label TEXT NOT NULL,
            speed_value INTEGER NOT NULL, source TEXT NOT NULL,
            osm_id TEXT NOT NULL,
            PRIMARY KEY(region,id))""")
        db.execSQL("CREATE INDEX idx_region_lat_lon ON markers(lat,lon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never silently drop downloaded regional data.
        throw IllegalStateException("Cần nâng cấp kho OSM thủ công.")
    }

    /** Max 8 MiB per region; use per-province extracts from an offline PBF pipeline. */
    fun importRegion(region: String, input: InputStream, updatedMs: Long =
        System.currentTimeMillis()): Int {
        require(region.matches(Regex("[a-z0-9_-]{2,45}"))) {
            "Mã vùng chỉ được chứa chữ thường, số, - và _."
        }
        val bytes = readBounded(input, 8 * 1024 * 1024)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getString("type") == "FeatureCollection")
        val features = root.getJSONArray("features")
        require(features.length() <= 25000) { "Chia dữ liệu thành các vùng nhỏ hơn." }
        val markers = ArrayList<Marker>(features.length())
        val keys = HashSet<String>()
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geom = feature.getJSONObject("geometry")
            require(geom.getString("type") == "Point") { "Chỉ hỗ trợ biển báo Point." }
            val coords = geom.getJSONArray("coordinates")
            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)
            require(lat.isFinite() && lat in -90.0..90.0 &&
                lon.isFinite() && lon in -180.0..180.0)
            val props = feature.getJSONObject("properties")
            require(props.getString("source") == "OpenStreetMap") {
                "Chỉ nhận dữ liệu ghi rõ nguồn OpenStreetMap."
            }
            val osmId = props.getString("osm_id")
            require(osmId.matches(Regex("(node|way)/[0-9]{1,16}"))) {
                "Thiếu OSM ID có thể truy xuất."
            }
            val type = props.getString("type")
            require(type in allowedTypes) { "Loại biển báo OSM chưa hỗ trợ." }
            val speed = if (type == "speed") props.getInt("value") else 0
            require(speed == 0 || type == "speed" && speed in 5..160)
            require(type != "speed" || speed in 5..160)
            val label = if (type == "speed") "$speed" else labelFor(type)
            require(keys.add(osmId)) { "OSM ID bị trùng trong vùng." }
            markers.add(Marker(osmId, region, lat, lon, type, label, speed,
                "OpenStreetMap", osmId))
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("markers", "region=?", arrayOf(region))
            val values = ContentValues()
            for (m in markers) {
                values.clear()
                values.put("region", m.region)
                values.put("id", m.id)
                values.put("lat", m.lat)
                values.put("lon", m.lon)
                values.put("type", m.type)
                values.put("label", m.label)
                values.put("speed_value", m.value)
                values.put("source", m.source)
                values.put("osm_id", m.osmId)
                check(db.insertOrThrow("markers", null, values) != -1L)
            }
            val regionValues = ContentValues().apply {
                put("region", region)
                put("updated_ms", updatedMs)
                put("feature_count", markers.size)
            }
            db.insertWithOnConflict("regions", null, regionValues,
                SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return markers.size
    }

    fun regions(): List<Region> {
        val result = ArrayList<Region>()
        readableDatabase.rawQuery(
            "SELECT region,feature_count,updated_ms FROM regions ORDER BY region", null
        ).use { cursor ->
            while (cursor.moveToNext())
                result.add(Region(cursor.getString(0), cursor.getInt(1), cursor.getLong(2)))
        }
        return result
    }

    fun nearby(lat: Double, lon: Double, radiusM: Double = 2_000.0,
        limit: Int = 120): List<Marker> {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 &&
            lon in -180.0..180.0 && radiusM in 1.0..10000.0 && limit in 1..300)
        val dlat = radiusM / 111_320.0
        val dlon = radiusM / (111_320.0 * cos(Math.toRadians(lat))
            .coerceAtLeast(0.05))
        val found = ArrayList<Marker>()
        readableDatabase.rawQuery(
            """SELECT id,region,lat,lon,type,label,speed_value,source,osm_id
               FROM markers WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
               LIMIT 600""",
            arrayOf((lat-dlat).toString(),(lat+dlat).toString(),
                (lon-dlon).toString(),(lon+dlon).toString())
        ).use { c ->
            while (c.moveToNext()) {
                val m = Marker(c.getString(0),c.getString(1),c.getDouble(2),
                    c.getDouble(3),c.getString(4),c.getString(5),c.getInt(6),
                    c.getString(7),c.getString(8))
                val north = (m.lat-lat)*111320.0
                val east = (m.lon-lon)*111320.0 * cos(Math.toRadians(lat))
                if (north*north+east*east <= radiusM*radiusM) found.add(m)
            }
        }
        return found.sortedBy {
            val dy = (it.lat-lat)*111320.0
            val dx = (it.lon-lon)*111320.0*cos(Math.toRadians(lat))
            dx*dx+dy*dy
        }.take(limit)
    }

    companion object {
        val allowedTypes = setOf("speed","camera","redlight_camera","stop",
            "yield","no_entry","no_overtaking","no_parking",
            "school","bump","signals","railway","curve","other")
        fun labelFor(type: String): String = when(type) {
            "camera", "redlight_camera" -> "CAM"
            "stop" -> "STOP"
            "no_entry" -> "CẤM"
            "school" -> "HS"
            "no_overtaking" -> "VƯỢT"
            "signals" -> "ĐÈN"
            else -> "!"
        }

        /** Reader cap is enforced while streaming, not after allocating. */
        fun readBounded(input: InputStream, cap: Int): ByteArray {
            require(cap > 0)
            val out = java.io.ByteArrayOutputStream(minOf(cap,8192))
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                if (n == 0) continue
                require(out.size() <= cap-n) { "Dữ liệu OSM vùng quá lớn." }
                out.write(chunk,0,n)
            }
            return out.toByteArray()
        }
    }
}
