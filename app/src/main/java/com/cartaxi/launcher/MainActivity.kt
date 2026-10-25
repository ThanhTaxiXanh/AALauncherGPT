package com.cartaxi.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private val reqPick = 2
    private val reqLocation = 1
    private val reqReportExport = 3
    private var exportWithGps = false
    private val settingsRepository by lazy {
        DashboardSettingsRepository(this)
    }
    private var pad = 0
    private lateinit var preview: ImageView
    private lateinit var eventBox: LinearLayout
    private lateinit var reportBox: LinearLayout
    private val reportStore by lazy {
        TrafficReportStore(File(filesDir, "traffic_reports_v1.json"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "CarTaxiLauncher"
            textSize = 22f
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = (240 * density).toInt()
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val pick = Button(this).apply {
            text = "Chọn ảnh mã QR từ điện thoại"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                this@MainActivity.startActivityForResult(i, reqPick)
            }
        }
        val perm = Button(this).apply {
            text = "Cấp quyền vị trí (đo tốc độ)"
            setOnClickListener {
                // Android 12+ bắt buộc xin FINE cùng COARSE trong một yêu cầu, nếu không hệ thống bỏ qua.
                this@MainActivity.requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    reqLocation
                )
            }
        }
        val evTitle = TextView(this).apply {
            text = "Sự kiện hiển thị trên widget Lịch"
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }
        eventBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addEv = Button(this).apply {
            text = "Thêm sự kiện"
            setOnClickListener { this@MainActivity.showAddDialog() }
        }

        root.addView(title)
        root.addView(preview)
        root.addView(pick)
        root.addView(perm)
        addDashboardSettings(root)
        root.addView(evTitle)
        root.addView(eventBox)
        root.addView(addEv)
        val reportsTitle = TextView(this).apply {
            text = "Báo cáo giao thông (lưu offline trên điện thoại)"
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }
        reportBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addReport = Button(this).apply {
            text = "Tạo báo cáo giao thông"
            setOnClickListener { this@MainActivity.showReportDialog() }
        }
        root.addView(reportsTitle)
        root.addView(reportBox)
        root.addView(addReport)
        val exportReports = Button(this).apply {
            text = "Xuất báo cáo JSON"
            setOnClickListener { showExportDialog() }
        }
        val eraseGps = Button(this).apply {
            text = "Xóa toàn bộ GPS khỏi báo cáo"
            setOnClickListener { confirmEraseGps() }
        }
        root.addView(exportReports)
        root.addView(eraseGps)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        refreshPreview()
        refreshEvents()
        refreshReports()
    }

    // ---------- Phase 5: centralized phone configuration ----------
    private fun addDashboardSettings(root: LinearLayout) {
        val title = TextView(this).apply {
            text = "Trung tâm cấu hình Dashboard"
            textSize = 20f
            setPadding(0, pad, 0, 0)
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Áp dụng cho màn hình Android Auto khi ứng dụng cập nhật. " +
                "Bố cục và kích thước các ô sẽ được tùy chỉnh ở Phase 6."
            textSize = 13f
        })
        val current = try {
            settingsRepository.read()
        } catch (e: Exception) {
            root.addView(TextView(this).apply {
                text = "Cài đặt có lỗi hoặc thuộc phiên bản mới hơn. " +
                    "Dữ liệu đang được giữ nguyên; chỉ khôi phục khi bạn đồng ý."
            })
            addSettingsResetButton(root)
            return
        }
        fun toggle(
            title: String, selected: Boolean,
            update: (DashboardSettings, Boolean) -> DashboardSettings
        ) {
            val box = CheckBox(this).apply {
                text = title
                isChecked = selected
            }
            var restoring = false
            box.setOnCheckedChangeListener { _, checked ->
                if (restoring) return@setOnCheckedChangeListener
                try {
                    // Merge against the latest snapshot rather than overwriting
                    // a different setting modified since this screen opened.
                    settingsRepository.change { latest -> update(latest, checked) }
                } catch (e: Exception) {
                    logErr("MainActivity:settingsToggle", e)
                    restoring = true
                    box.isChecked = !checked
                    restoring = false
                    Toast.makeText(this@MainActivity,
                        "Không lưu được cài đặt.", Toast.LENGTH_LONG).show()
                }
            }
            root.addView(box)
        }
        toggle("Hiện ô Lịch / Sự kiện", current.showCalendar) { value, checked ->
            value.copy(showCalendar = checked)
        }
        toggle("Hiện ô mã QR", current.showQr) { value, checked ->
            value.copy(showQr = checked)
        }
        toggle("Hiện ô Biển báo / tốc độ", current.showSigns) { value, checked ->
            value.copy(showSigns = checked)
        }
        toggle("Hiện tên đường trên bản đồ", current.showStreetName) { value, checked ->
            value.copy(showStreetName = checked)
        }
        toggle("Hiển thị báo cáo cá nhân trên bản đồ (chưa xác minh)",
            current.showPersonalReportsOnMap) { value, checked ->
            value.copy(showPersonalReportsOnMap = checked)
        }
        root.addView(TextView(this).apply {
            text = "Chế độ sáng / tối của bản đồ"
            textSize = 15f
        })
        val modes = MapThemeMode.entries
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes.map { it.title }
            )
            setSelection(modes.indexOf(current.mapTheme))
        }
        root.addView(modeSpinner)
        val saveTheme = Button(this).apply {
            text = "Lưu chế độ bản đồ"
            setOnClickListener {
                try {
                    settingsRepository.change {
                        it.copy(mapTheme = modes[modeSpinner.selectedItemPosition])
                    }
                    Toast.makeText(this@MainActivity,
                        "Đã cập nhật chế độ bản đồ.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    logErr("MainActivity:settingsTheme", e)
                    Toast.makeText(this@MainActivity,
                        "Không lưu được chế độ bản đồ.", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(saveTheme)
        addSettingsResetButton(root)
    }

    private fun addSettingsResetButton(root: LinearLayout) {
        root.addView(Button(this).apply {
            text = "Khôi phục cấu hình Dashboard mặc định"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Khôi phục cài đặt mặc định?")
                    .setMessage("Chỉ đặt lại lựa chọn hiển thị Dashboard. " +
                        "Lịch, mã QR, báo cáo và tọa độ trong báo cáo vẫn được giữ nguyên. " +
                        "Báo cáo cá nhân trên bản đồ sẽ được ẩn.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Khôi phục") { _, _ ->
                        try {
                            settingsRepository.reset()
                            recreate()
                        } catch (e: Exception) {
                            logErr("MainActivity:settingsReset", e)
                            Toast.makeText(this@MainActivity,
                                "Không thể khôi phục cài đặt.", Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqLocation) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val msg = if (fine) {
            "Đã cấp quyền vị trí chính xác"
        } else {
            "Cần cấp vị trí \"Chính xác\" (không phải \"Gần đúng\") để đo tốc độ"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- sự kiện ----------

    private fun refreshEvents() {
        eventBox.removeAllViews()
        val list = Events.load(this)
        if (list.isEmpty()) {
            eventBox.addView(TextView(this).apply { text = "(chưa có sự kiện nào)" })
            return
        }
        for (e in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply {
                text = "• ${e.title} - ${Events.whenText(e)}"
                textSize = 15f
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    Events.save(this@MainActivity, Events.load(this@MainActivity).filter { it.id != e.id })
                    this@MainActivity.refreshEvents()
                }
            }
            row.addView(del)
            eventBox.addView(row)
        }
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply { hint = "Tên sự kiện (ví dụ: Giỗ Mẹ)" }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbSolar = RadioButton(this).apply {
            text = "Dương lịch"
            id = View.generateViewId()
        }
        val rbLunar = RadioButton(this).apply {
            text = "Âm lịch"
            id = View.generateViewId()
        }
        group.addView(rbSolar)
        group.addView(rbLunar)
        group.check(rbSolar.id)
        val day = EditText(this).apply {
            hint = "Ngày"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val month = EditText(this).apply {
            hint = "Tháng (bỏ qua nếu lặp hằng tháng)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val year = EditText(this).apply {
            hint = "Năm (chỉ cần khi chọn Một lần)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val repeat = Spinner(this)
        repeat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Lặp hằng năm", "Lặp hằng tháng", "Một lần")
        )
        box.addView(title)
        box.addView(group)
        box.addView(day)
        box.addView(month)
        box.addView(year)
        box.addView(repeat)

        AlertDialog.Builder(this)
            .setTitle("Thêm sự kiện")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val name = title.text.toString().trim()
                val lunar = group.checkedRadioButtonId == rbLunar.id
                val rep = repeat.selectedItemPosition
                val d = day.text.toString().toIntOrNull() ?: 0
                val m = if (rep == 1) 1 else (month.text.toString().toIntOrNull() ?: 0)
                val y = if (rep == 2) (year.text.toString().toIntOrNull() ?: 0) else 0
                val maxDay = if (lunar) 30 else 31
                val ok = name.isNotEmpty() && d in 1..maxDay && m in 1..12 && (rep != 2 || y >= 2000)
                if (!ok) {
                    Toast.makeText(this, "Thông tin chưa hợp lệ, hãy kiểm tra lại", Toast.LENGTH_LONG).show()
                } else {
                    val list = Events.load(this).toMutableList()
                    list.add(CalEvent(System.currentTimeMillis(), name, lunar, d, m, y, rep))
                    Events.save(this, list)
                    refreshEvents()
                }
            }
            .show()
    }

    // ---------- báo cáo giao thông Phase 4: lưu cục bộ ----------
    private fun refreshReports() {
        reportBox.removeAllViews()
        val saved = try {
            reportStore.list()
        } catch (e: Exception) {
            reportBox.addView(TextView(this).apply {
                text = "Không đọc được dữ liệu báo cáo. File gốc được giữ nguyên."
            })
            logErr("MainActivity:reportRead", e)
            return
        }
        if (saved.isEmpty()) {
            reportBox.addView(TextView(this).apply {
                text = "(chưa có báo cáo nào; không gửi dữ liệu lên mạng)"
            })
            return
        }
        reportBox.addView(TextView(this).apply {
            text = "${saved.size} báo cáo cục bộ · chưa xác minh · chỉ lưu trên máy"
            textSize = 13f
        })
        for (report in saved) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val details = TextView(this).apply {
                val location = if (report.location != null)
                    " · Có tọa độ GPS (±${report.location.accuracyM.toInt()} m)"
                else " · Không đính kèm GPS"
                val timestamp = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                ).format(java.util.Date(report.createdAtMs))
                text = "${report.kind.title} · ${report.severity.title}\n" +
                    "${report.note.ifBlank { "(không có ghi chú)" }}\n" +
                    "$timestamp$location\nChưa xác minh · Chưa chia sẻ"
                textSize = 14f
            }
            row.addView(details, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            val delete = Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Xóa báo cáo cục bộ?")
                        .setMessage("Báo cáo này sẽ bị xóa khỏi điện thoại.")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa") { _, _ ->
                            try {
                                reportStore.delete(report.id)
                                refreshReports()
                            } catch (e: Exception) {
                                logErr("MainActivity:reportDelete", e)
                                Toast.makeText(this@MainActivity,
                                    "Không thể xóa báo cáo; dữ liệu vẫn được giữ.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
            }
            val edit = Button(this).apply {
                text = "Sửa"
                setOnClickListener { showReportDialog(report) }
            }
            row.addView(edit)
            row.addView(delete)
            reportBox.addView(row)
        }
    }

    /** Only reads a recent location after a deliberate GPS opt-in. */
    private fun recentReportLocation(): TrafficReportLocation? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return null
            if (!gps.hasAccuracy() || gps.accuracy !in 0f..50f) return null
            val ageNs = SystemClock.elapsedRealtimeNanos() - gps.elapsedRealtimeNanos
            if (ageNs < 0L || ageNs > 120_000_000_000L) return null
            TrafficReportLocation(gps.latitude, gps.longitude, gps.accuracy.toDouble())
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun showReportDialog(existing: TrafficReport? = null) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val types = TrafficReportKind.entries
        val severityTypes = TrafficReportSeverity.entries
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                types.map { it.title }
            )
        }
        val severity = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                severityTypes.map { it.title }
            )
        }
        type.setSelection(existing?.kind?.ordinal ?: 0)
        severity.setSelection(existing?.severity?.ordinal ?: 0)
        val note = EditText(this).apply {
            hint = "Ghi chú (không quá 240 ký tự, không bắt buộc)"
            setText(existing?.note.orEmpty())
            setSingleLine(false)
            maxLines = 3
        }
        val attachGps = CheckBox(this).apply {
            text = "Đính kèm vị trí GPS hiện tại (tùy chọn)"
            isChecked = existing?.location != null
        }
        val gpsHint = TextView(this).apply {
            text = "Mặc định không lưu tọa độ. Dữ liệu chưa được xác minh và không tải lên mạng."
            textSize = 13f
        }
        var selectedLocation: TrafficReportLocation? = existing?.location
        if (selectedLocation != null) {
            gpsHint.text = "Vị trí GPS đã lưu sẽ được giữ nếu vẫn chọn đính kèm."
        }
        attachGps.setOnCheckedChangeListener { _, checked ->
            selectedLocation = if (checked) recentReportLocation() else null
            gpsHint.text = if (!checked) {
                "Không đính kèm vị trí GPS."
            } else if (selectedLocation == null) {
                "Chưa có GPS chính xác, mới trong 2 phút. Bỏ chọn GPS hoặc cấp quyền vị trí."
            } else {
                val loc = selectedLocation!!
                "Vị trí sẽ lưu trên máy: ${"%.5f".format(loc.lat)}, " +
                    "${"%.5f".format(loc.lon)} (±${loc.accuracyM.toInt()} m)"
            }
        }
        wrap.addView(TextView(this).apply { text = "Loại sự cố" })
        wrap.addView(type)
        wrap.addView(TextView(this).apply { text = "Mức độ" })
        wrap.addView(severity)
        wrap.addView(note)
        wrap.addView(attachGps)
        wrap.addView(gpsHint)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Tạo báo cáo · Offline"
                else "Chỉnh sửa báo cáo cục bộ")
            .setView(wrap)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu cục bộ", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (attachGps.isChecked && selectedLocation == null) {
                    Toast.makeText(this, "Không có GPS hợp lệ; hãy bỏ chọn GPS.",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    if (existing == null) {
                        reportStore.create(
                            types[type.selectedItemPosition],
                            severityTypes[severity.selectedItemPosition],
                            note.text.toString().trim(),
                            if (attachGps.isChecked) selectedLocation else null
                        )
                    } else {
                        checkNotNull(reportStore.update(
                            existing.id,
                            types[type.selectedItemPosition],
                            severityTypes[severity.selectedItemPosition],
                            note.text.toString().trim(),
                            if (attachGps.isChecked) selectedLocation else null
                        )) { "Báo cáo đã bị xóa." }
                    }
                    dialog.dismiss()
                    refreshReports()
                    Toast.makeText(this, "Đã lưu báo cáo offline", Toast.LENGTH_SHORT).show()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, e.message ?: "Dữ liệu báo cáo không hợp lệ",
                        Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    logErr("MainActivity:reportSave", e)
                    Toast.makeText(this,
                        "Không lưu được báo cáo. File cũ được giữ nguyên.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun confirmEraseGps() {
        AlertDialog.Builder(this)
            .setTitle("Xóa vĩnh viễn tọa độ GPS?")
            .setMessage(
                "Chỉ xóa vị trí khỏi các báo cáo trên điện thoại. " +
                    "Loại sự cố, ghi chú và thời gian sẽ được giữ lại. " +
                    "Dữ liệu đã xuất trước đây không thể thu hồi."
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa GPS") { _, _ ->
                try {
                    val count = reportStore.clearAllLocations()
                    refreshReports()
                    Toast.makeText(this, "Đã xóa GPS khỏi $count báo cáo",
                        Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    logErr("MainActivity:eraseReportsGps", e)
                    Toast.makeText(this, "Không thể xóa GPS; dữ liệu cũ được giữ.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun showExportDialog() {
        val include = CheckBox(this).apply {
            text = "Bao gồm tọa độ GPS (có thể tiết lộ vị trí cá nhân)"
            isChecked = false
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(this)
            .setTitle("Xuất báo cáo cá nhân")
            .setMessage("Tạo file JSON qua bộ chọn nơi lưu của Android. " +
                "Mặc định loại bỏ toàn bộ GPS; file được xuất có thể được ứng dụng khác đọc.")
            .setView(include)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Chọn nơi lưu") { _, _ ->
                exportWithGps = include.isChecked
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE,
                        "AALauncherGPT_reports_${System.currentTimeMillis()}.json")
                }
                try {
                    startActivityForResult(intent, reqReportExport)
                } catch (e: Exception) {
                    exportWithGps = false
                    Toast.makeText(this, "Không mở được nơi lưu tệp.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun writeReportExport(uri: Uri) {
        val include = exportWithGps
        exportWithGps = false
        try {
            val payload = reportStore.exportJson(include)
            contentResolver.openOutputStream(uri, "w")?.use {
                it.write(payload.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: throw IllegalStateException("Không mở được tệp xuất.")
            Toast.makeText(this, "Đã xuất báo cáo JSON", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            logErr("MainActivity:reportExport", e)
            Toast.makeText(this, "Xuất báo cáo thất bại.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- ảnh QR ----------

    private fun refreshPreview() {
        val f = File(filesDir, "qr.png")
        if (f.exists()) preview.setImageBitmap(BitmapFactory.decodeFile(f.path))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqReportExport) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { writeReportExport(it) } ?: run { exportWithGps = false }
            } else exportWithGps = false
            return
        }
        if (requestCode == reqPick && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            saveQr(uri)
        }
    }

    private fun saveQr(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw IllegalStateException("decode")
            File(filesDir, "qr.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            refreshPreview()
            Toast.makeText(this, "Đã lưu mã QR", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
        }
    }
}
