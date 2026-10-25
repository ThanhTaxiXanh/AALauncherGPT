package Thanh.TaxiDriver.AALauncher.reports



import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** User-entered report types. All reports remain local, unverified and unpublished. */
enum class TrafficReportKind(val title: String) {
    CONGESTION("Ùn tắc"),
    COLLISION("Tai nạn"),
    ROADWORK("Thi công"),
    CLOSURE("Đường bị chặn"),
    HAZARD("Chướng ngại vật"),
    OTHER("Khác")
}
enum class TrafficReportSeverity(val title: String) {
    LOW("Nhẹ"), MEDIUM("Trung bình"), HIGH("Nghiêm trọng")
}

data class TrafficReportLocation(
    val lat: Double, val lon: Double, val accuracyM: Double
) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0)
        require(lon.isFinite() && lon in -180.0..180.0)
        require(accuracyM.isFinite() && accuracyM in 0.0..50.0)
    }
}

data class TrafficReport(
    val id: String,
    val kind: TrafficReportKind,
    val severity: TrafficReportSeverity,
    val note: String,
    val createdAtMs: Long,
    val location: TrafficReportLocation? = null
) {
    init {
        require(id.isNotBlank() && id.length <= 80)
        require(createdAtMs > 0L)
        require(note.length <= 240)
    }
}

/**
 * No network I/O. V1 intentionally has no shared/server state.
 * Corrupt/unknown schema causes a read error, never an empty-list overwrite.
 * Writes use a same-directory temp file, fsync, then atomic replacement.
 */
class TrafficReportStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    companion object {
        const val MAX_REPORTS = 300
        private const val MAX_BYTES = 512_000L
        private const val VERSION = 1
    }

    @Synchronized fun list(): List<TrafficReport> = read().sortedByDescending { it.createdAtMs }

    @Synchronized fun create(
        kind: TrafficReportKind,
        severity: TrafficReportSeverity,
        note: String,
        location: TrafficReportLocation? = null
    ): TrafficReport {
        require(note.length <= 240) { "Ghi chú tối đa 240 ký tự." }
        val previous = read()
        check(previous.size < MAX_REPORTS) {
            "Đã đạt giới hạn $MAX_REPORTS báo cáo; hãy xóa bớt báo cáo cũ."
        }
        val candidate = TrafficReport(idFactory(), kind, severity, note.trim(), clock(), location)
        check(previous.none { it.id == candidate.id }) { "Trùng mã báo cáo." }
        write(previous + candidate)
        return candidate
    }


    /** Edit in place: report ID and original creation time never change. */
    @Synchronized fun update(
        id: String, kind: TrafficReportKind,
        severity: TrafficReportSeverity, note: String,
        location: TrafficReportLocation?
    ): TrafficReport? {
        require(note.length <= 240) { "Ghi chú tối đa 240 ký tự." }
        val reports = read()
        val index = reports.indexOfFirst { it.id == id }
        if (index < 0) return null
        val changed = reports.toMutableList()
        val edited = reports[index].copy(
            kind = kind, severity = severity, note = note.trim(), location = location
        )
        changed[index] = edited
        if (edited != reports[index]) write(changed)
        return edited
    }

    /** Privacy erasure; all non-location fields are preserved. */
    @Synchronized fun clearAllLocations(): Int {
        val reports = read()
        val count = reports.count { it.location != null }
        if (count > 0) write(reports.map { it.copy(location = null) })
        return count
    }

    /**
     * Explicit user-triggered export only. GPS is omitted by default,
     * including all lat/lon/accuracy keys; never send it to a server.
     */
    @Synchronized fun exportJson(includeGps: Boolean = false): String {
        val exported = JSONArray()
        for (r in read()) {
            val obj = JSONObject()
                .put("id", r.id)
                .put("kind", r.kind.name)
                .put("severity", r.severity.name)
                .put("note", r.note)
                .put("createdAtMs", r.createdAtMs)
                .put("verified", false)
            if (includeGps && r.location != null) {
                obj.put("location", JSONObject()
                    .put("lat", r.location.lat)
                    .put("lon", r.location.lon)
                    .put("accuracyM", r.location.accuracyM))
            }
            exported.put(obj)
        }
        return JSONObject()
            .put("version", 1)
            .put("scope", "personal_unverified")
            .put("includesGps", includeGps)
            .put("reports", exported).toString(2)
    }

    @Synchronized fun delete(id: String): Boolean {
        val reports = read()
        val remaining = reports.filterNot { it.id == id }
        if (remaining.size == reports.size) return false
        write(remaining)
        return true
    }

    private fun read(): List<TrafficReport> {
        if (!file.exists()) return emptyList()
        require(file.isFile && file.length() in 1..MAX_BYTES) {
            "File báo cáo không hợp lệ hoặc vượt giới hạn."
        }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        require(root.getInt("version") == VERSION) {
            "Phiên bản báo cáo chưa được hỗ trợ."
        }
        val arr = root.getJSONArray("reports")
        require(arr.length() <= MAX_REPORTS) { "Quá nhiều báo cáo." }
        val reports = ArrayList<TrafficReport>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val location = if (r.isNull("location") || !r.has("location")) null
                else r.getJSONObject("location").let { obj ->
                    TrafficReportLocation(
                        obj.getDouble("lat"), obj.getDouble("lon"),
                        obj.getDouble("accuracyM")
                    )
                }
            reports.add(
                TrafficReport(
                    r.getString("id"),
                    TrafficReportKind.valueOf(r.getString("kind")),
                    TrafficReportSeverity.valueOf(r.getString("severity")),
                    r.getString("note"),
                    r.getLong("createdAtMs"),
                    location
                )
            )
        }
        require(reports.map { it.id }.toSet().size == reports.size) {
            "Trùng mã trong kho báo cáo."
        }
        return reports
    }

    private fun write(reports: List<TrafficReport>) {
        require(reports.size <= MAX_REPORTS)
        val arr = JSONArray()
        for (r in reports) {
            val loc = r.location?.let {
                JSONObject().put("lat", it.lat).put("lon", it.lon)
                    .put("accuracyM", it.accuracyM)
            }
            arr.put(JSONObject()
                .put("id", r.id)
                .put("kind", r.kind.name)
                .put("severity", r.severity.name)
                .put("note", r.note)
                .put("createdAtMs", r.createdAtMs)
                .put("location", loc ?: JSONObject.NULL)
            )
        }
        val payload = JSONObject()
            .put("version", VERSION)
            .put("reports", arr)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(payload.size.toLong() <= MAX_BYTES) { "Kho báo cáo đã đầy." }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        try {
            temp.outputStream().use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
