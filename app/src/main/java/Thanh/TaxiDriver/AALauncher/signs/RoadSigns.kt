package Thanh.TaxiDriver.AALauncher.signs

import Thanh.TaxiDriver.AALauncher.core.logErr
import Thanh.TaxiDriver.AALauncher.map.NativeMap
import Thanh.TaxiDriver.AALauncher.road.ObservedRoadTurnTracker
import Thanh.TaxiDriver.AALauncher.road.OsmRoadGraph
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestriction
import Thanh.TaxiDriver.AALauncher.road.OsmTurnRestrictions
import Thanh.TaxiDriver.AALauncher.road.RoadSignRouteMatcher
import Thanh.TaxiDriver.AALauncher.road.RoadSpatialIndex
import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatcher

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// type: speed | camera | stop | yield | signals | bump | other
data class SignItem(
    val lat: Double,
    val lon: Double,
    val type: String,
    val label: String,   // chữ ngắn vẽ trong vòng tròn trên bản đồ
    val name: String,    // tên đầy đủ hiện ở widget
    val value: Int,      // km/h nếu có
    val facing: Double? = null, // OSM direction: physical sign facing, opposite travel
    val osmNodeId: Long? = null,
    /** OSM traffic_sign:forward/backward applies to the parent way node order. */
    val wayDirection: Int? = null
)

data class UpcomingSign(val item: SignItem, val distM: Int)

data class SignsState(
    val limit: Int?,
    val estimated: Boolean,
    val upcoming: List<UpcomingSign>,
    val status: String,
    /** OSM way ID of the speed-limit source, not a lane identifier. */
    val roadWayId: Long? = null
) {
    companion object {
        val EMPTY = SignsState(null, false, emptyList(), "Đang chờ vị trí")
    }
}

// Giới hạn tốc độ + biển báo lấy từ OpenStreetMap (Overpass API).
// Bản đồ OpenFreeMap không chứa maxspeed / biển báo nên phải tải riêng.
class RoadSigns(private val cacheFile: File, supplementalGeoJson: String = "") {
    companion object {
        private val ENDPOINTS = arrayOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        private const val RADIUS = 900
        private const val REFETCH_M = 350.0
        private const val MAX_AGE_MS = 15 * 60_000L
        private const val MAX_DATA_DISTANCE_M = 900.0
        private const val RETRY_MS = 30_000L
        private const val MAX_RESPONSE_CHARS = 3_500_000

        private const val HIGHWAYS =
            "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
                "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
    }

    private class Way(
        val geometry: SpeedWayGeometry,
        val highway: String,
        val all: Int?,
        val fwd: Int?,
        val bwd: Int?,
        val allConditional: String?,
        val fwdConditional: String?,
        val bwdConditional: String?
    )
    private class Data(
        val lat: Double,
        val lon: Double,
        val ways: List<Way>,
        val geometries: List<SpeedWayGeometry>,
        val graph: OsmRoadGraph,
        val index: RoadSpatialIndex,
        val signs: List<SignItem>,
        val timeMs: Long,
        val stale: Boolean,
        val savedAtEpochMs: Long
    )

    @Volatile private var data: Data? = null
    @Volatile private var fetching = false
    @Volatile private var error = ""
    @Volatile private var lastHttpStatus = ""
    private var lastTry = -100000L
    private val diskCache = MultiRegionSignCache(cacheFile)
    private val requestGate = OverpassRequestGate()
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var offlineRegions: List<OfflineRegionSigns> = emptyList()
    @Volatile private var hintsLoading = false
    private var hintCenterLat = Double.NaN
    private var hintCenterLon = Double.NaN

    private val speedWayMatcher = SpeedWayMatcher()
    private val turnTracker = ObservedRoadTurnTracker()
    private var lastWayId: Long? = null
    private var lastData: Data? = null
    private val extraSigns: List<SignItem> = parseOpenGeoJson(supplementalGeoJson)
    private var shown: Int? = null
    private var shownEst = false
    private var shownMs = 0L
    private var pendV: Int? = null
    private var pendEst = false
    private var pendN = 0

    // Disk data is loaded asynchronously only near the current GPS position;
    // startup does not parse every region into memory.

    /** Release the background executor when its owning NativeMap is destroyed. */
    fun close() {
        exec.shutdownNow()
    }

    // ---------- tải dữ liệu ----------

    fun request(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        loadNearbyHints(lat, lon)
        val now = SystemClock.elapsedRealtime()
        // Only freshly downloaded data is allowed to serve live speed limits.
        // Query coalescing and retries are independent of the offline archive.
        if (!requestGate.begin(lat, lon, now)) return
        fetching = true
        exec.execute {
            var success = false
            try {
                val txt = download(lat, lon)
                val savedAtMs = System.currentTimeMillis()
                val parsed = parse(txt, lat, lon, SystemClock.elapsedRealtime(), false, savedAtMs)
                data = parsed
                error = ""
                lastHttpStatus = ""
                success = true
                try {
                    diskCache.write(lat, lon, txt, savedAtMs)
                } catch (e: Exception) {
                    logErr("RoadSigns:cacheWrite", e)
                }
            } catch (e: Exception) {
                error = "Lỗi dữ liệu biển báo (kết nối/Overpass)"
                logErr("RoadSigns:network", e)
            } finally {
                requestGate.complete(success, SystemClock.elapsedRealtime())
                fetching = false
            }
        }
    }

    private data class OfflineRegionSigns(
        val centerLat: Double,
        val centerLon: Double,
        val savedAtEpochMs: Long,
        val signs: List<SignItem>
    )

    /**
     * Called on the map/UI thread: only schedules work. Slow disk access and
     * parsing never block GPS, the ImageReader, or the map rendering loop.
     */
    private fun loadNearbyHints(lat: Double, lon: Double) {
        if (hintsLoading) return
        if (hintCenterLat.isFinite() && dist(lat, lon, hintCenterLat, hintCenterLon) < 250.0) return
        hintsLoading = true
        hintCenterLat = lat
        hintCenterLon = lon
        exec.execute {
            try {
                val snapshots = diskCache.readNearby(lat, lon, MAX_DATA_DISTANCE_M, 4)
                offlineRegions = snapshots.mapNotNull { snapshot ->
                    try {
                        val signs = parseHintSigns(snapshot.payload).map { item ->
                            item.copy(
                                type = "offline_hint", label = "?", value = 0,
                                name = "OSM lưu offline (chưa xác minh): " + item.name
                            )
                        }
                        OfflineRegionSigns(
                            snapshot.lat, snapshot.lon, snapshot.savedAtEpochMs, signs
                        )
                    } catch (e: Exception) {
                        logErr("RoadSigns:regionParse", e)
                        null
                    }
                }
            } catch (e: Exception) {
                logErr("RoadSigns:regionCache", e)
                offlineRegions = emptyList()
            } finally {
                hintsLoading = false
            }
        }
    }

    private fun parseHintSigns(raw: String): List<SignItem> {
        val elements = JSONObject(raw).getJSONArray("elements")
        val signs = ArrayList<SignItem>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "node" ||
                !e.has("lat") || !e.has("lon")) continue
            val t = e.optJSONObject("tags") ?: continue
            classify(t, e.getDouble("lat"), e.getDouble("lon"))?.let { item ->
                signs.add(item.copy(facing = parseFacing(t.str("direction"))))
            }
        }
        return signs
    }

    /** Expired and far-away archived regions are discarded at *display* time. */
    private fun nearbyArchived(lat: Double, lon: Double): List<SignItem> {
        val now = System.currentTimeMillis()
        return offlineRegions.asSequence()
            .filter { region ->
                SignCachePolicy.classify(
                    fromDisk = true,
                    ageElapsedMs = 0L,
                    savedAtEpochMs = region.savedAtEpochMs,
                    nowEpochMs = now,
                    distanceM = dist(lat, lon, region.centerLat, region.centerLon),
                    maxDistanceM = MAX_DATA_DISTANCE_M
                ) == SignCacheUse.OFFLINE_HINT
            }
            .flatMap { it.signs.asSequence() }
            .toList()
    }

    private fun query(lat: Double, lon: Double): String {
        val a = "(around:$RADIUS,$lat,$lon)"
        return "[out:json][timeout:25];(" +
            "way$a[\"highway\"~\"^($HIGHWAYS)\$\"];" +
            "node$a[\"highway\"~\"^(stop|give_way|traffic_signals|speed_camera)\$\"];" +
            "node$a[\"enforcement\"=\"maxspeed\"];" +
            "node$a[\"traffic_calming\"];" +
            "node$a[\"traffic_sign\"];" +
            "node$a[\"traffic_sign:forward\"];" +
            "node$a[\"traffic_sign:backward\"];" +
            "node$a[\"overtaking\"=\"no\"];" +
            "node$a[\"maxspeed\"];" +
            "relation$a[\"type\"=\"restriction\"];" +
            ");out tags geom;"
    }

    private fun download(lat: Double, lon: Double): String {
        val body = ("data=" + URLEncoder.encode(query(lat, lon), "UTF-8")).toByteArray()
        var last: Exception? = null
        for (ep in ENDPOINTS) {
            try {
                val c = URL(ep).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true
                    c.requestMethod = "POST"
                    c.connectTimeout = 8000
                    c.readTimeout = 30000
                    c.doOutput = true
                    c.setRequestProperty("User-Agent", "CarTaxiLauncher/0.1 (Android)")
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.setRequestProperty("Accept", "application/json")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    if (code != 200) throw IllegalStateException("HTTP $code")
                    // Enforce the cap during streaming, before allocating unbounded JSON.
                    val s = c.inputStream.bufferedReader(Charsets.UTF_8).use {
                        BoundedOverpassBody.read(it, MAX_RESPONSE_CHARS)
                    }
                    val json = JSONObject(s)
                    if (json.has("remark")) throw IllegalStateException("Overpass query incomplete")
                    if (!json.has("elements")) throw IllegalStateException("Overpass returned no elements array")
                    return s
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no endpoint")
    }

    // ---------- phân tích ----------

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    private fun parseSpeed(s: String?): Int? {
        if (s == null) return null
        val t = s.trim().lowercase()
        if (t == "walk") return 7
        val m = Regex("^(\\d{1,3})(?:\\s*(mph|km/h|kmh|kph))?$").matchEntire(t) ?: return null
        var v = m.groupValues[1].toInt()
        if (m.groupValues[2] == "mph") v = (v * 1.609).roundToInt()
        return if (v in 5..160) v else null
    }

    private fun parse(
        txt: String, lat: Double, lon: Double,
        now: Long, stale: Boolean, savedAtEpochMs: Long
    ): Data {
        val els = JSONObject(txt).getJSONArray("elements")
        val ways = ArrayList<Way>()
        val signs = ArrayList<SignItem>()
        val restrictions = ArrayList<OsmTurnRestriction>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tg = e.optJSONObject("tags") ?: continue
            if (e.optString("type") == "relation" &&
                tg.str("type") == "restriction") {
                val members = e.optJSONArray("members")
                val from = ArrayList<Long>()
                val to = ArrayList<Long>()
                val viaNodes = ArrayList<Long>()
                var unusual = members == null
                if (members != null) {
                    for (k in 0 until members.length()) {
                        val member = members.optJSONObject(k) ?: continue
                        val role = member.optString("role")
                        val kind = member.optString("type")
                        val id = member.optLong("ref", -1L)
                        if (id <= 0L) { unusual = true; continue }
                        when (role) {
                            "from" -> if (kind == "way") from.add(id) else unusual = true
                            "to" -> if (kind == "way") to.add(id) else unusual = true
                            "via" -> if (kind == "node") viaNodes.add(id) else unusual = true
                            else -> unusual = true
                        }
                    }
                }
                if (from.size == 1) restrictions.add(
                    OsmTurnRestriction(
                        relationId = e.optLong("id", -1L),
                        fromWayId = from.single(),
                        toWayId = to.singleOrNull(),
                        viaNodeId = viaNodes.singleOrNull(),
                        restriction = tg.str("restriction:motorcar") ?: tg.str("restriction"),
                        unsupported = unusual || to.size != 1 || viaNodes.size != 1 ||
                            tg.str("except") != null ||
                            tg.str("restriction:conditional") != null ||
                            tg.str("restriction:motorcar:conditional") != null ||
                            tg.str("restriction:vehicle") != null ||
                            tg.str("restriction:motor_vehicle") != null
                    )
                )
            } else if (e.optString("type") == "way") {
                val hw = tg.str("highway") ?: continue
                val g = e.optJSONArray("geometry") ?: continue
                if (g.length() < 2) continue
                val pts = DoubleArray(g.length() * 2)
                for (j in 0 until g.length()) {
                    val p = g.getJSONObject(j)
                    pts[2 * j] = p.getDouble("lat")
                    pts[2 * j + 1] = p.getDouble("lon")
                }
                val nodeArray = e.optJSONArray("nodes")
                val nodeIds = if (nodeArray != null && nodeArray.length() == g.length()) {
                    LongArray(nodeArray.length()) { j -> nodeArray.optLong(j, -1L) }
                } else LongArray(0)
                val wayId = e.optLong("id", -1L)
                // A missing way ID cannot support road-specific speed reporting.
                if (wayId <= 0L) continue
                val oneWay = when (tg.str("oneway")?.trim()?.lowercase()) {
                    "yes", "true", "1" -> 1
                    "-1" -> -1
                    "no", "false", "0" -> 0
                    null -> if (tg.str("junction") == "roundabout") 1 else 0
                    else -> 2 // Unknown rules are not traversable.
                }
                val roadLevel = tg.str("layer")?.toIntOrNull() ?: when {
                    tg.str("bridge") == "yes" -> 1
                    tg.str("tunnel") == "yes" -> -1
                    else -> 0
                }
                ways.add(
                    Way(
                        SpeedWayGeometry(wayId, roadLevel, pts, nodeIds, oneWay),
                        hw,
                        parseSpeed(tg.str("maxspeed")),
                        parseSpeed(tg.str("maxspeed:forward")),
                        parseSpeed(tg.str("maxspeed:backward")),
                        tg.str("maxspeed:conditional"),
                        tg.str("maxspeed:forward:conditional"),
                        tg.str("maxspeed:backward:conditional")
                    )
                )
            } else if (e.optString("type") == "node") {
                if (!e.has("lat") || !e.has("lon")) continue
                classify(tg, e.getDouble("lat"), e.getDouble("lon"))?.let { item ->
                    val forwardTag = tg.str("traffic_sign:forward")
                    val backwardTag = tg.str("traffic_sign:backward")
                    val wayDirection = if (tg.str("traffic_sign") != null) null
                        else if (forwardTag != null && backwardTag == null) 1
                        else if (backwardTag != null && forwardTag == null) -1
                        else null
                    signs.add(item.copy(
                        facing = parseFacing(tg.str("direction")),
                        osmNodeId = e.optLong("id", -1L).takeIf { it > 0L },
                        wayDirection = wayDirection
                    ))
                }
            }
        }
        val geometries = ways.map { it.geometry }
        return Data(
            lat, lon, ways, geometries,
            OsmRoadGraph(geometries, OsmTurnRestrictions(restrictions)),
            RoadSpatialIndex(geometries, signs + extraSigns),
            signs, now, stale, savedAtEpochMs
        )
    }

    // OSM direction=* describes the way the sign physically faces. A driver
    // approaches from the opposite direction. Support numeric + cardinal values.
    private fun parseFacing(raw: String?): Double? {
        val v = raw?.trim()?.uppercase() ?: return null
        val degrees = when (v) {
            "N" -> 0.0
            "NE" -> 45.0
            "E" -> 90.0
            "SE" -> 135.0
            "S" -> 180.0
            "SW" -> 225.0
            "W" -> 270.0
            "NW" -> 315.0
            else -> v.toDoubleOrNull()
        } ?: return null
        return if (degrees.isFinite()) ((degrees % 360.0) + 360.0) % 360.0 else null
    }

    private fun classify(t: JSONObject, lat: Double, lon: Double): SignItem? {
        val hw = t.str("highway")
        val ms = parseSpeed(t.str("maxspeed"))
        val trafficSign = (t.str("traffic_sign") ?: t.str("traffic_sign:forward") ?: t.str("traffic_sign:backward"))?.lowercase() ?: ""
        return when {
            hw == "speed_camera" || t.str("enforcement") == "maxspeed" ->
                SignItem(lat, lon, "camera", "CAM",
                    if (ms != null) "Camera tốc độ $ms" else "Camera tốc độ", ms ?: 0)
            t.str("enforcement") == "traffic_signals" ->
                SignItem(lat, lon, "redlight_camera", "CAM", "Camera giám sát đèn đỏ", 0)
            hw == "stop" -> SignItem(lat, lon, "stop", "STOP", "Biển dừng", 0)
            hw == "give_way" -> SignItem(lat, lon, "yield", "NH", "Nhường đường", 0)
            hw == "traffic_signals" -> SignItem(lat, lon, "signals", "ĐÈN", "Đèn giao thông", 0)
            t.str("traffic_calming") != null -> SignItem(lat, lon, "bump", "GỜ", "Gờ giảm tốc", 0)
            t.str("overtaking") == "no" || trafficSign.contains("no_overtaking") || trafficSign.contains("no-overtaking") || trafficSign.contains("p.125") ->
                SignItem(lat, lon, "no_overtaking", "CẤM", "Cấm vượt", 0)
            ms != null -> SignItem(lat, lon, "speed", ms.toString(), "Giới hạn $ms km/h", ms)
            trafficSign.isNotBlank() -> fromTrafficSign(trafficSign, lat, lon)
            else -> null
        }
    }

    private fun fromTrafficSign(ts: String, lat: Double, lon: Double): SignItem {
        val s = ts.lowercase()
        val num = Regex("\\[(\\d{2,3})\\]").find(s)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            (s.contains("maxspeed") || s.contains("127")) && num != null ->
                SignItem(lat, lon, "speed", num.toString(), "Giới hạn $num km/h", num)
            s.contains("maxspeed") || s.contains("127") ->
                SignItem(lat, lon, "other", "!", "Biển giới hạn tốc độ", 0)
            s.contains("stop") || s.contains("122") -> SignItem(lat, lon, "stop", "STOP", "Biển dừng", 0)
            s.contains("give_way") || s.contains("yield") -> SignItem(lat, lon, "yield", "NH", "Nhường đường", 0)
            s.contains("no_overtaking") || s.contains("no-overtaking") || s.contains("p.125") ->
                SignItem(lat, lon, "no_overtaking", "CẤM", "Cấm vượt", 0)
            s.contains("no_entry") || s.contains("no-entry") || s.contains("p.102") ->
                SignItem(lat, lon, "no_entry", "⛔", "Cấm đi vào", 0)
            s.contains("no_parking") || s.contains("p.131a") ->
                SignItem(lat, lon, "no_parking", "P", "Cấm đỗ xe", 0)
            s.contains("maxheight") || s.contains("max_height") ->
                SignItem(lat, lon, "height", "H", "Giới hạn chiều cao (xem biển thực tế)", 0)
            s.contains("children") || s.contains("school") || s.contains("w.225") ->
                SignItem(lat, lon, "school", "HS", "Chú ý trẻ em/trường học", 0)
            s.contains("railway_crossing") || s.contains("level_crossing") ->
                SignItem(lat, lon, "railway", "TÀU", "Đường sắt giao cắt", 0)
            s.contains("traffic_signals") || s.contains("traffic_lights") ->
                SignItem(lat, lon, "signals", "ĐÈN", "Đèn giao thông phía trước", 0)
            s.contains("dangerous_curve") || s.contains("w.201") ->
                SignItem(lat, lon, "curve", "↷", "Đường cong nguy hiểm", 0)
            else -> SignItem(lat, lon, "other", "!", "Biển báo: " + ts.take(24), 0)
        }
    }

    // Open-data supplement is distributed only when its source/license is verified.
    // Coordinates are GeoJSON [longitude, latitude]. Never auto-infer legal limits.
    private fun parseOpenGeoJson(text: String): List<SignItem> {
        if (text.isBlank()) return emptyList()
        return try {
            val a = JSONObject(text).getJSONArray("features")
            val out = ArrayList<SignItem>()
            for (i in 0 until a.length()) {
                val obj = a.getJSONObject(i)
                val props = obj.optJSONObject("properties") ?: continue
                val geom = obj.optJSONObject("geometry") ?: continue
                if (geom.optString("type") != "Point") continue
                val xy = geom.optJSONArray("coordinates") ?: continue
                if (xy.length() < 2) continue
                val lon = xy.getDouble(0)
                val lat = xy.getDouble(1)
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                if (!props.optBoolean("verified", false) || props.optString("source").isBlank()) continue
                val value = props.optInt("value", 0)
                val sign = when (props.optString("type")) {
                    "speed" -> if (value in 5..160) SignItem(lat, lon, "speed", "$value", "Giới hạn $value km/h", value) else null
                    "no_overtaking" -> SignItem(lat, lon, "no_overtaking", "CẤM", "Cấm vượt", 0)
                    "camera" -> SignItem(lat, lon, "camera", "CAM", "Camera tốc độ", 0)
                    "stop" -> SignItem(lat, lon, "stop", "STOP", "Biển dừng", 0)
                    "yield" -> SignItem(lat, lon, "yield", "NH", "Nhường đường", 0)
                    "bump" -> SignItem(lat, lon, "bump", "GỜ", "Gờ giảm tốc", 0)
                    else -> null
                }
                if (sign != null) out.add(sign)
            }
            out
        } catch (e: Exception) {
            logErr("RoadSigns:openGeoJson", e)
            emptyList()
        }
    }

    // ---------- truy vấn ----------

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * 111320.0
        return sqrt(dx * dx + dy * dy)
    }

    private fun angDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    /**
     * Verified supplements win; fresh OSM data is shown normally; archived
     * OSM from multiple regions is a grey '?' hint only. No disk I/O here.
     */
    fun nearby(lat: Double, lon: Double, radius: Double, max: Int): List<SignItem> {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            radius <= 0.0 || max <= 0) return emptyList()
        val d = data
        val sourceMode = cacheUsage(d, lat, lon)
        val live = if (sourceMode == SignCacheUse.LIVE) d!!.signs else emptyList()
        // The most recent in-memory response may have just expired, even if
        // disk hints have not been reloaded for this stationary location.
        val expiredLive = if (sourceMode == SignCacheUse.OFFLINE_HINT) d!!.signs.map { item ->
            item.copy(
                type = "offline_hint", label = "?", value = 0,
                name = "OSM lưu offline (chưa xác minh): " + item.name
            )
        } else emptyList()
        val archived = expiredLive + nearbyArchived(lat, lon)
        // Priority before sorting: verified > fresh OSM > archived hints.
        val selected = ArrayList<SignItem>(min(max, extraSigns.size + live.size + archived.size))
        for (sign in extraSigns + live + archived) {
            if (dist(lat, lon, sign.lat, sign.lon) > radius) continue
            if (selected.any { prior ->
                    dist(prior.lat, prior.lon, sign.lat, sign.lon) < 6.0 &&
                        (prior.type == sign.type ||
                            prior.type == "offline_hint" ||
                            sign.type == "offline_hint")
                }) continue
            selected.add(sign)
        }
        return selected.sortedBy { dist(lat, lon, it.lat, it.lon) }.take(max)
    }

    private fun cacheUsage(d: Data?, lat: Double, lon: Double): SignCacheUse {
        if (d == null) return SignCacheUse.NONE
        return SignCachePolicy.classify(
            fromDisk = d.stale,
            ageElapsedMs = SystemClock.elapsedRealtime() - d.timeMs,
            savedAtEpochMs = d.savedAtEpochMs,
            nowEpochMs = System.currentTimeMillis(),
            distanceM = dist(lat, lon, d.lat, d.lon),
            maxDistanceM = MAX_DATA_DISTANCE_M,
            liveTtlMs = MAX_AGE_MS
        )
    }

    private fun commit(v: Int, est: Boolean, now: Long) {
        val cur = shown
        if (cur == null) {
            shown = v; shownEst = est; shownMs = now; pendV = null; pendN = 0
        } else if (cur == v && est == shownEst) {
            shownMs = now; pendV = null; pendN = 0
        } else {
            if (pendV == v && pendEst == est) pendN++ else {
                pendV = v; pendEst = est; pendN = 1
            }
            if (pendN >= 2) {
                shown = v; shownEst = est; shownMs = now; pendV = null; pendN = 0
            }
        }
    }

    /** One update per second; accuracyM is horizontal GPS uncertainty in metres. */
    fun state(
        lat: Double, lon: Double, heading: Double,
        moving: Boolean, accuracyM: Double, gpsFixMs: Long
    ): SignsState {
        val d = data
        val now = SystemClock.elapsedRealtime()
        if (d != null && d !== lastData) {
            lastData = d
            lastWayId = null
            speedWayMatcher.reset()
            turnTracker.reset()
            shown = null
            pendV = null
            pendN = 0
        }
        val cacheMode = cacheUsage(d, lat, lon)
        val usable = cacheMode == SignCacheUse.LIVE
        if (!usable) {
            shown = null
            lastWayId = null
            speedWayMatcher.reset()
            pendV = null
            pendN = 0
        }
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0

        // 1) Match an OSM way using filtered GPS and its reported accuracy.
        // Ambiguous parallel, crossing or stacked roads produce an unknown limit.
        val match = if (usable) speedWayMatcher.match(
            lat, lon, heading, moving, accuracyM, d!!.geometries, d.index
        ) else null
        val w = match?.let { d!!.ways[it.wayIndex] }
        if (w != null) {
            val selected = match!!
            // Never carry a previous road's limit onto a new way.
            if (lastWayId != selected.wayId) {
                shown = null
                pendV = null
                pendN = 0
                lastWayId = selected.wayId
            }
            val wallTimeMs = System.currentTimeMillis()
            val explicit = when {
                !moving || !heading.isFinite() -> {
                    if (w.fwdConditional != null || w.bwdConditional != null ||
                        w.fwd != null || w.bwd != null
                    ) null else ConditionalSpeedLimit.resolve(w.all, w.allConditional, wallTimeMs)
                }
                selected.forward -> ConditionalSpeedLimit.resolve(
                    w.fwd ?: w.all, w.fwdConditional ?: w.allConditional, wallTimeMs
                )
                else -> ConditionalSpeedLimit.resolve(
                    w.bwd ?: w.all, w.bwdConditional ?: w.allConditional, wallTimeMs
                )
            }
            if (explicit != null) commit(explicit, false, now)
            else { shown = null; pendV = null; pendN = 0 }
        } else {
            shown = null
            lastWayId = null
            pendV = null
            pendN = 0
            speedWayMatcher.reset()
        }

        // 2) Only show route-relative warnings on an unambiguous matched way.
        // No navigation route is available: never invent a turn at a junction.
        // Multiple UI renders of the same GPS fix cannot count as evidence
        // of an observed turn. Until fresh GPS fixes confirm the chosen road,
        // speed may show OSM data, but upcoming sign warnings are withheld.
        val observed = if (usable && now - gpsFixMs in 0L..4_000L && match != null)
            turnTracker.observe(
                match, lat, lon, heading, moving, accuracyM, gpsFixMs, d!!.graph
            ) else {
                turnTracker.reset()
                null
            }
        val ups = if (observed != null) {
            RoadSignRouteMatcher.upcoming(
                lat, lon, heading, moving, accuracyM,
                observed, d!!.geometries, d.signs + extraSigns, d.graph, d.index
            )
        } else emptyList()

        val status = when {
            !usable && (cacheMode == SignCacheUse.OFFLINE_HINT ||
                nearbyArchived(lat, lon).isNotEmpty()) ->
                "Biển báo OSM offline: tham khảo, không có giới hạn tốc độ hợp lệ"
            !usable && fetching -> "Đang tải dữ liệu đường/biển báo..."
            !usable && error.isNotEmpty() -> error
            !usable -> "Chưa có dữ liệu đường cập nhật"
            d!!.ways.isEmpty() -> "OSM chưa có dữ liệu đường tại đây"
            shown == null && d.signs.isEmpty() && extraSigns.isEmpty() -> "OSM thiếu tốc độ và biển báo khu vực này"
            shown == null && w == null -> "Chưa khớp chắc chắn đoạn đường OSM"
            shown == null -> "Giới hạn không xác định / điều kiện chưa xác minh"
            else -> "Giới hạn từ OSM (tham khảo)"
        }
        return SignsState(shown, shownEst, ups, status, if (shown != null) match?.wayId else null)
    }
}
