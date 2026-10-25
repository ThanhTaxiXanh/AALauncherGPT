package Thanh.TaxiDriver.AALauncher.ui

import Thanh.TaxiDriver.AALauncher.calendar.CalEvent
import Thanh.TaxiDriver.AALauncher.calendar.EventReminderScheduler
import Thanh.TaxiDriver.AALauncher.calendar.EventReminderSettings
import Thanh.TaxiDriver.AALauncher.calendar.Events
import Thanh.TaxiDriver.AALauncher.calendar.LunarCalendar
import Thanh.TaxiDriver.AALauncher.core.logErr
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardLayoutPreview
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardLayouts
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardPanel
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardSettings
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardSettingsRepository
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardTile
import Thanh.TaxiDriver.AALauncher.dashboard.MapThemeMode
import Thanh.TaxiDriver.AALauncher.dashboard.PhoneDashboardActivity
import Thanh.TaxiDriver.AALauncher.media.LocalAudioService
import Thanh.TaxiDriver.AALauncher.qr.QrLibraryStore
import Thanh.TaxiDriver.AALauncher.reports.TrafficReport
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportKind
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportLocation
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportSeverity
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportStore

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
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
    private val reqNotifications = 4
    private val reqAudio = 5
    private val reqExportQr = 6
    private var exportQrId: String? = null
    private val qrLibrary by lazy { QrLibraryStore(filesDir) }
    private lateinit var qrBox: LinearLayout
    private lateinit var mediaStatus: TextView
    private var mediaFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val mediaPrefs by lazy { getSharedPreferences("local_media", MODE_PRIVATE) }
    private var pendingReminderSettings: EventReminderSettings? = null
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
        root.addView(Button(this).apply {
            text = "Mở Custom Dashboard đa ô (điện thoại)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PhoneDashboardActivity::class.java))
            }
        })
        root.addView(TextView(this).apply {
            text = "Dashboard tùy chỉnh chỉ chạy trên điện thoại Android. " +
                "Nếu đang lái xe, không chỉnh sửa bố cục hoặc chạm màn hình."
            textSize = 13f
        })
        root.addView(pick)
        qrBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(qrBox)
        addMediaControls(root)
        root.addView(perm)
        addDashboardSettings(root)
        root.addView(evTitle)
        val today = java.time.LocalDate.now()
        val lunarToday = LunarCalendar.solarToLunar(
            today.dayOfMonth, today.monthValue, today.year
        )
        root.addView(TextView(this).apply {
            text = "Hôm nay ${today.dayOfMonth}/${today.monthValue}/${today.year} (DL) · " +
                "${lunarToday.day}/${lunarToday.month}/${lunarToday.year} (ÂL)" +
                (if (lunarToday.leap) " · tháng nhuận" else "") +
                "\nNăm ${LunarCalendar.canChi(lunarToday.year)} · múi giờ tính Âm lịch UTC+7"
            textSize = 14f
        })
        root.addView(eventBox)
        root.addView(addEv)
        addReminderControls(root)
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
        refreshQrList()
        refreshEvents()
        refreshReports()
        try {
            EventReminderScheduler.refresh(this)
        } catch (e: Exception) {
            logErr("MainActivity:reminderRefresh", e)
        }
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
        addDashboardLayoutEditor(root)
    }

    // ---------- Phase 6: phone-only interactive 12x12 Layout Builder ----------
    private fun addDashboardLayoutEditor(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "Phase 6 · Dashboard Layout Builder"
            textSize = 20f
            setPadding(0, pad, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Chọn 1 trong 8 bố cục, hoặc chạm vào ô để chọn, " +
                "kéo để di chuyển và kéo góc phải-dưới để đổi kích thước. " +
                "Chỉ thay đổi khi bấm Lưu. Thanh điều khiển cố định bên trái."
            textSize = 13f
        })
        val initial = try {
            settingsRepository.read()
        } catch (e: Exception) {
            root.addView(TextView(this).apply {
                text = "Không mở được Layout Builder: cần xử lý cấu hình lỗi trước."
            })
            return
        }
        var draft = DashboardLayouts.active(initial)
        val preview = DashboardLayoutPreview(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (225 * resources.displayMetrics.density).toInt()
            )
            display(draft)
        }
        val message = TextView(this).apply {
            text = "Bản xem trước chưa lưu · có thể điều chỉnh các ô."
            textSize = 13f
        }
        val selection = TextView(this).apply {
            text = "Ô đang chọn: Bản đồ"
            textSize = 15f
        }
        preview.onSelectionChanged = { panel ->
            selection.text = "Ô đang chọn: ${panel.title}"
        }
        preview.onEdited = { updated ->
            draft = updated
            message.text = "Đã chỉnh sửa bố cục · bấm Lưu để áp dụng lên Dashboard."
        }
        preview.onRejected = { message.text = it }
        val presetOptions = DashboardLayouts.presets
        val selectPreset = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presetOptions.map { it.title }
            )
            setSelection(presetOptions.indexOfFirst { it.id == initial.layoutPresetId }
                .coerceAtLeast(0))
        }
        root.addView(selectPreset)
        root.addView(Button(this).apply {
            text = "Áp dụng preset đã chọn"
            setOnClickListener {
                val preset = presetOptions[selectPreset.selectedItemPosition]
                try {
                    settingsRepository.change {
                        it.copy(layoutPresetId = preset.id, useCustomLayout = false)
                    }
                    draft = preset.layout
                    preview.display(draft)
                    message.text = "Đã áp dụng: ${preset.title}. Bố cục tùy chỉnh cũ vẫn được giữ."
                } catch (e: Exception) {
                    logErr("MainActivity:layoutPreset", e)
                    message.text = "Lưu preset thất bại. Cài đặt vẫn được giữ nguyên."
                }
            }
        })
        root.addView(preview)
        root.addView(selection)
        root.addView(message)

        // Keyboard/switch-access friendly alternative to dragging.
        fun addControls(left: String, right: String,
                        moveLeft: (DashboardTile) -> DashboardTile,
                        moveRight: (DashboardTile) -> DashboardTile) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun adjust(label: String, edit: (DashboardTile) -> DashboardTile) {
                row.addView(Button(this).apply {
                    text = label
                    setOnClickListener {
                        val panel = preview.selectedPanel
                        if (panel == DashboardPanel.ICONS) {
                            message.text = "Thanh điều khiển được cố định để bảo đảm thao tác."
                            return@setOnClickListener
                        }
                        try {
                            val tile = edit(draft.tile(panel))
                            val next = draft.edit(panel, tile.x, tile.y, tile.width, tile.height)
                            draft = next
                            preview.display(next)
                            message.text = "Bố cục đã chỉnh sửa nhưng chưa lưu."
                        } catch (_: IllegalArgumentException) {
                            message.text = "Không thể thay đổi: ô chồng lấn hoặc vượt giới hạn."
                        }
                    }
                }, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
            adjust(left, moveLeft)
            adjust(right, moveRight)
            root.addView(row)
        }
        addControls("← Trái", "Phải →",
            { it.copy(x = it.x - 1) }, { it.copy(x = it.x + 1) })
        addControls("↑ Lên", "Xuống ↓",
            { it.copy(y = it.y - 1) }, { it.copy(y = it.y + 1) })
        addControls("Hẹp hơn", "Rộng hơn",
            { it.copy(width = it.width - 1) }, { it.copy(width = it.width + 1) })
        addControls("Thấp hơn", "Cao hơn",
            { it.copy(height = it.height - 1) }, { it.copy(height = it.height + 1) })

        root.addView(Button(this).apply {
            text = "Lưu bố cục tùy chỉnh và sử dụng"
            setOnClickListener {
                try {
                    settingsRepository.change {
                        it.copy(customLayout = draft, useCustomLayout = true)
                    }
                    message.text = "Đã lưu bố cục tùy chỉnh. Android Auto sẽ cập nhật."
                } catch (e: Exception) {
                    logErr("MainActivity:layoutSave", e)
                    message.text = "Lưu thất bại; bố cục đang dùng không thay đổi."
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Khôi phục bố cục tùy chỉnh đã lưu"
            setOnClickListener {
                try {
                    val saved = settingsRepository.read().customLayout
                    if (saved == null) {
                        message.text = "Chưa có bố cục tùy chỉnh đã lưu."
                    } else {
                        settingsRepository.change { it.copy(useCustomLayout = true) }
                        draft = saved
                        preview.display(draft)
                        message.text = "Đã khôi phục và áp dụng bố cục tùy chỉnh."
                    }
                } catch (e: Exception) {
                    logErr("MainActivity:layoutRestore", e)
                    message.text = "Không khôi phục được bố cục."
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Bỏ chỉnh sửa chưa lưu"
            setOnClickListener {
                try {
                    draft = DashboardLayouts.active(settingsRepository.read())
                    preview.display(draft)
                    message.text = "Đã hiển thị lại bố cục đang được dùng."
                } catch (e: Exception) {
                    message.text = "Không đọc được bố cục hiện tại."
                }
            }
        })
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
        if (requestCode == reqNotifications) {
            val desired = pendingReminderSettings
            pendingReminderSettings = null
            if (desired != null && EventReminderScheduler.permissionGranted(this)) {
                try {
                    EventReminderScheduler.save(this, desired)
                    Toast.makeText(this, "Đã bật nhắc lịch cá nhân.",
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    logErr("MainActivity:reminderPermission", e)
                }
            } else Toast.makeText(this,
                "Chưa được cấp quyền thông báo; nhắc lịch vẫn tắt.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode != reqLocation) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val msg = if (fine) {
            "Đã cấp quyền vị trí chính xác"
        } else {
            "Cần cấp vị trí \"Chính xác\" (không phải \"Gần đúng\") để đo tốc độ"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- Phase 7: Lịch Âm/Dương, chỉnh sửa và nhắc lịch ----------
    private fun addReminderControls(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "Nhắc lịch cá nhân (offline, mặc định tắt)"
            textSize = 18f
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Dự kiến khoảng 09:00 theo giờ điện thoại; Android có thể giao thông báo muộn " +
                "do tiết kiệm pin. Không tự gửi lịch hoặc vị trí lên mạng."
            textSize = 13f
        })
        val current = EventReminderScheduler.read(this)
        val enabled = CheckBox(this).apply {
            text = "Bật thông báo nhắc sự kiện"
            isChecked = current.enabled
        }
        val holidays = CheckBox(this).apply {
            text = "Nhắc thêm ngày lễ mặc định (không bao gồm Mùng một/Rằm hàng tháng)"
            isChecked = current.includeHolidays
        }
        val options = listOf(0, 1, 3, 7)
        val advance = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Chỉ ngày diễn ra", "Trước 1 ngày và ngày diễn ra",
                    "Trước 3 ngày và ngày diễn ra",
                    "Trước 7 ngày và ngày diễn ra"))
            setSelection(options.indexOf(current.advanceDays).coerceAtLeast(0))
        }
        root.addView(enabled)
        root.addView(holidays)
        root.addView(advance)
        root.addView(Button(this).apply {
            text = "Lưu cài đặt nhắc lịch"
            setOnClickListener {
                val desired = EventReminderSettings(
                    enabled.isChecked, options[advance.selectedItemPosition],
                    holidays.isChecked
                )
                if (desired.enabled && !EventReminderScheduler.permissionGranted(this@MainActivity)) {
                    pendingReminderSettings = desired
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            reqNotifications)
                    else pendingReminderSettings = null
                    return@setOnClickListener
                }
                try {
                    EventReminderScheduler.save(this@MainActivity, desired)
                    Toast.makeText(this@MainActivity, "Đã lưu cài đặt nhắc lịch.",
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    logErr("MainActivity:saveReminderSettings", e)
                    Toast.makeText(this@MainActivity, "Không lưu được cài đặt nhắc lịch.",
                        Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun refreshEvents() {
        eventBox.removeAllViews()
        val saved = Events.load(this)
        val today = java.time.LocalDate.now()
        if (saved.isEmpty()) {
            eventBox.addView(TextView(this).apply { text = "(chưa có sự kiện cá nhân)" })
            return
        }
        val upcoming = saved.mapNotNull { e ->
            Events.nextDate(e, today)?.let { e to it }
        }.sortedBy { it.second }.take(6)
        eventBox.addView(TextView(this).apply {
            text = "Sắp đến: " +
                (if (upcoming.isEmpty()) "không có" else upcoming.joinToString(" · ") {
                    "${it.first.title.take(25)} (${it.second.dayOfMonth}/${it.second.monthValue})"
                })
            textSize = 13f
        })
        for (e in saved) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply {
                text = "• ${e.title} - ${Events.whenText(e)}"
                textSize = 15f
            }
            row.addView(tv, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(Button(this).apply {
                text = "Sửa"
                setOnClickListener { showAddDialog(e) }
            })
            row.addView(Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Xóa sự kiện?")
                        .setMessage("Xóa ${e.title} và các nhắc lịch tương ứng?")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa") { _, _ ->
                            try {
                                Events.save(this@MainActivity,
                                    Events.load(this@MainActivity).filter { it.id != e.id })
                                refreshEvents()
                            } catch (err: Exception) {
                                logErr("MainActivity:deleteEvent", err)
                                Toast.makeText(this@MainActivity, "Không xóa được sự kiện.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
            })
            eventBox.addView(row)
        }
    }

    private fun showAddDialog(existing: CalEvent? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply {
            hint = "Tên sự kiện (tối đa 100 ký tự)"
            setText(existing?.title.orEmpty())
        }
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
        group.check(if (existing?.lunar == true) rbLunar.id else rbSolar.id)
        val day = EditText(this).apply {
            hint = "Ngày"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing?.day?.toString().orEmpty())
        }
        val month = EditText(this).apply {
            hint = "Tháng (bỏ qua nếu lặp hằng tháng)"
            inputType = InputType.TYPE_CLASS_NUMBER
            if (existing != null && existing.repeat != 1) setText(existing.month.toString())
        }
        val year = EditText(this).apply {
            hint = "Năm (chỉ cần khi chọn Một lần)"
            inputType = InputType.TYPE_CLASS_NUMBER
            if (existing?.repeat == 2) setText(existing.year.toString())
        }
        val repeat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Lặp hằng năm", "Lặp hằng tháng", "Một lần"))
            setSelection(existing?.repeat?.coerceIn(0, 2) ?: 0)
        }
        val leap = CheckBox(this).apply {
            text = "Tháng Âm lịch nhuận (chỉ hằng năm hoặc một lần)"
            isChecked = existing?.leapMonth == true
        }
        box.addView(title)
        box.addView(group)
        box.addView(day)
        box.addView(month)
        box.addView(year)
        box.addView(repeat)
        box.addView(leap)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Thêm sự kiện" else "Chỉnh sửa sự kiện")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lunar = group.checkedRadioButtonId == rbLunar.id
                val rep = repeat.selectedItemPosition
                val newEvent = CalEvent(
                    existing?.id ?: System.currentTimeMillis(),
                    title.text.toString().trim(), lunar,
                    day.text.toString().toIntOrNull() ?: 0,
                    if (rep == 1) 1 else (month.text.toString().toIntOrNull() ?: 0),
                    if (rep == 2) year.text.toString().toIntOrNull() ?: 0 else 0,
                    rep,
                    leapMonth = lunar && rep != 1 && leap.isChecked
                )
                if (!Events.validateUserEvent(newEvent)) {
                    Toast.makeText(this@MainActivity,
                        "Sự kiện không hợp lệ. Kiểm tra ngày, tháng nhuận và tên.",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    val list = Events.load(this@MainActivity).toMutableList()
                    if (existing == null) {
                        if (list.size >= 250) throw IllegalStateException("Đã đủ 250 sự kiện.")
                        // Guard against IDs coinciding in the same millisecond.
                        val unique = newEvent.copy(
                            id = generateSequence(newEvent.id) { it + 1L }
                                .first { value -> list.none { entry -> entry.id == value } }
                        )
                        list.add(unique)
                    } else {
                        val index = list.indexOfFirst { entry -> entry.id == existing.id }
                        check(index >= 0) { "Sự kiện đã được xóa." }
                        list[index] = newEvent
                    }
                    Events.save(this@MainActivity, list)
                    dialog.dismiss()
                    refreshEvents()
                } catch (e: Exception) {
                    logErr("MainActivity:saveEvent", e)
                    Toast.makeText(this@MainActivity,
                        "Không lưu được sự kiện; dữ liệu cũ được giữ nguyên.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
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

    // ---------- Phase 8 · QR collection and audio ----------
    private fun refreshQrList() {
        qrBox.removeAllViews()
        val items = try { qrLibrary.entries() } catch (e: Exception) {
            logErr("MainActivity:qrList", e)
            qrBox.addView(TextView(this).apply {
                text = "Thư viện QR không đọc được; không ghi đè dữ liệu cũ."
            })
            return
        }
        val active = qrLibrary.current()?.id
        qrBox.addView(TextView(this).apply {
            text = "Thư viện QR cá nhân (${items.size}/30 ảnh)"
            textSize = 17f
        })
        for (entry in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(TextView(this).apply {
                text = (if (entry.id == active) "● " else "○ ") + entry.title
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Chọn"
                isEnabled = entry.id != active
                setOnClickListener {
                    try {
                        qrLibrary.select(entry.id)
                        refreshPreview()
                        refreshQrList()
                    } catch (e: Exception) { logErr("MainActivity:qrSelect", e) }
                }
            })
            row.addView(Button(this).apply {
                text = "Đổi tên"
                setOnClickListener {
                    val input = EditText(this@MainActivity).apply { setText(entry.title) }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Đổi tên QR")
                        .setView(input)
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Lưu") { _, _ ->
                            try {
                                qrLibrary.rename(entry.id, input.text.toString())
                                refreshQrList()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity,
                                    "Không đổi tên được QR.", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
            })
            row.addView(Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("Xóa ảnh QR?")
                        .setMessage("Không xóa ảnh gốc trên điện thoại.")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa") { _, _ ->
                            try {
                                qrLibrary.remove(entry.id)
                                refreshPreview()
                                refreshQrList()
                            } catch (e: Exception) {
                                logErr("MainActivity:qrRemove", e)
                            }
                        }.show()
                }
            })
            qrBox.addView(row)
        }
        qrBox.addView(Button(this).apply {
            text = "Xuất ảnh QR đang chọn (PNG)"
            isEnabled = active != null
            setOnClickListener {
                exportQrId = active
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/png"
                    putExtra(Intent.EXTRA_TITLE, "AALauncherGPT_QR.png")
                }, reqExportQr)
            }
        })
    }

    private fun addMediaControls(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "Media3 · Phát âm thanh cục bộ"
            textSize = 18f
            setPadding(0, pad, 0, 0)
        })
        mediaStatus = TextView(this).apply {
            text = mediaPrefs.getString("title", "Chưa chọn âm thanh")
        }
        root.addView(mediaStatus)
        root.addView(Button(this).apply {
            text = "Chọn tệp âm thanh (không tự phát)"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }, reqAudio)
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Phát"
            setOnClickListener { playLocalAudio() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Tạm dừng"
            setOnClickListener { mediaController?.pause() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        root.addView(Button(this).apply {
            text = "Bỏ tệp đã chọn"
            setOnClickListener {
                mediaController?.stop()
                mediaController?.clearMediaItems()
                mediaPrefs.edit().clear().apply()
                mediaStatus.text = "Chưa chọn âm thanh"
            }
        })
    }

    private fun playLocalAudio() {
        val uri = mediaPrefs.getString("uri", null)?.let(Uri::parse)
        if (uri == null || uri.scheme != "content") {
            Toast.makeText(this, "Chọn tệp âm thanh trước.", Toast.LENGTH_LONG).show()
            return
        }
        val controller = mediaController
        if (controller == null) {
            Toast.makeText(this, "MediaSession chưa kết nối.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error("Mất quyền đọc")
            val title = mediaPrefs.getString("title", "Âm thanh cá nhân")!!
            controller.setMediaItem(MediaItem.Builder().setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
            controller.prepare()
            controller.play()
            mediaStatus.text = "Đang phát: $title"
        } catch (e: Exception) {
            logErr("MainActivity:audioPlay", e)
            mediaStatus.text = "Không phát được. Hãy chọn lại tệp."
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, LocalAudioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        mediaFuture = future
        future.addListener({
            if (mediaFuture === future) try {
                mediaController = future.get()
            } catch (e: Exception) {
                logErr("MainActivity:mediaConnect", e)
            }
        }, mainExecutor)
    }

    override fun onStop() {
        mediaController = null
        mediaFuture?.let { MediaController.releaseFuture(it) }
        mediaFuture = null
        super.onStop()
    }

    // ---------- ảnh QR ----------

    private fun refreshPreview() {
        val f = File(filesDir, "qr.png")
        if (f.exists()) preview.setImageBitmap(BitmapFactory.decodeFile(f.path))
        else preview.setImageDrawable(null)
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
            data?.data?.let { saveQr(it) }
            return
        }
        if (requestCode == reqAudio && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                require(uri.scheme == "content")
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val title = uri.lastPathSegment?.takeLast(80) ?: "Âm thanh cá nhân"
                check(mediaPrefs.edit().putString("uri", uri.toString())
                    .putString("title", title).commit())
                mediaStatus.text = "Đã chọn: $title"
            } catch (e: Exception) {
                logErr("MainActivity:audioSelect", e)
                Toast.makeText(this, "Không lưu được quyền đọc âm thanh.",
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == reqExportQr) {
            val id = exportQrId
            exportQrId = null
            if (resultCode == RESULT_OK && id != null && data?.data != null) {
                try {
                    val file = qrLibrary.fileFor(id) ?: error("QR không tồn tại")
                    contentResolver.openOutputStream(data.data!!)?.use { dest ->
                        file.inputStream().use { it.copyTo(dest) }
                    } ?: error("Không mở được file đích")
                    Toast.makeText(this, "Đã xuất QR.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    logErr("MainActivity:qrExport", e)
                    Toast.makeText(this, "Xuất QR thất bại.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveQr(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: error("Không đọc được ảnh")
            require(bounds.outWidth in 1..2600 && bounds.outHeight in 1..2600)
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: error("Ảnh bị lỗi")
            val stream = ByteArrayOutputStream()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            } finally { bitmap.recycle() }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.take(60)
                ?.ifBlank { "Mã QR" } ?: "Mã QR"
            val added = qrLibrary.add(name, stream.toByteArray())
            qrLibrary.select(added.id)
            refreshPreview()
            refreshQrList()
            Toast.makeText(this, "Đã thêm mã QR vào thư viện.",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logErr("MainActivity:qrImport", e)
            Toast.makeText(this, "Không nhập được QR (ảnh quá lớn hoặc lỗi).",
                Toast.LENGTH_LONG).show()
        }
    }
}
