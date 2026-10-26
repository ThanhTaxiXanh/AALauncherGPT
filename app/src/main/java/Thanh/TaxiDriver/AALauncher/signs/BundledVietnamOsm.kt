package Thanh.TaxiDriver.AALauncher.signs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/**
 * Separate read-only OSM dataset extracted from Geofabrik on the CI runner.
 * Installed from APK assets on first access, not downloaded on device.
 * Existing user-imported regional SQLite and reports remain untouched.
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
            return null // Default debug APK may not include the bundled OSM asset.
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
                                require(bytes <= 160_000_000L) {
                                    "OSM bundle exceeds 160 MB unpacked"
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
                if (north*north+east*east > radiusM*radiusM) continue
                out.add(RegionalOsmStore.Marker(c.getString(0),c.getString(1),
                    c.getDouble(2),c.getDouble(3),c.getString(4),c.getString(5),
                    c.getInt(6),c.getString(7),c.getString(8)))
            }
        }
        return out.take(limit)
    }

    @Synchronized fun close() {
        database?.close()
        database = null
    }
}
