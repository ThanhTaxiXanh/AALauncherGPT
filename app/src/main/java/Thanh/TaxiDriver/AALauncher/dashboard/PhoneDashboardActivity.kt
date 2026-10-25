package Thanh.TaxiDriver.AALauncher.dashboard

import Thanh.TaxiDriver.AALauncher.calendar.EventLine
import Thanh.TaxiDriver.AALauncher.calendar.Events
import Thanh.TaxiDriver.AALauncher.car.CarService
import Thanh.TaxiDriver.AALauncher.core.logErr
import Thanh.TaxiDriver.AALauncher.map.NativeMap
import Thanh.TaxiDriver.AALauncher.signs.SignsState

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 10: full multi-tile Dashboard on the Android phone only.
 *
 * Never launched by CarService or attached to an Android Auto Surface.
 * Rendering is per-instance so phone touches cannot overwrite the
 * concurrent car screen's hit regions / bitmap cache.
 */
class PhoneDashboardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val settingsRepository by lazy {
        DashboardSettingsRepository(applicationContext)
    }
    private val maps by lazy { NativeMap(applicationContext, handler) }
    private val renderer = LauncherRenderer()
    private val lifetime = PhoneDashboardLifecycle()
    private lateinit var view: DashboardCanvasView
    private lateinit var status: TextView

    private var settings = DashboardSettings()
    private val qrFile by lazy { File(filesDir, "qr.png") }
    private var activeIcon = 0
    private var lastFixUptime = 0L
    private var speedKmh: Int? = null
    private var gpsRegistered = false
    private var gnssRegistered = false
    private var batteryPct = -1
    private var lastEventsKey = ""
    private var eventLines: List<EventLine> = emptyList()
    private var currentPermission = false

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!lifetime.active || !currentPermission) return
            val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (age < 0 || age > 10_000_000_000L) return
            lastFixUptime = SystemClock.uptimeMillis()
            speedKmh = if (location.hasSpeed() && location.hasAccuracy() &&
                location.speed >= 0f && location.accuracy <= 35f) {
                if (location.speed < 0.6f) 0
                else (location.speed * 3.6f).roundToInt()
            } else null
            maps.onLocation(location)
            view.postInvalidateOnAnimation()
        }
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            if (!lifetime.active) return
            var used = 0
            var totalCn0 = 0.0
            for (i in 0 until s.satelliteCount) {
                if (s.usedInFix(i)) {
                    used++
                    totalCn0 += s.getCn0DbHz(i)
                }
            }
            maps.setGnss(used, if (used == 0) 0.0 else totalCn0 / used)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!lifetime.active) return
            currentPermission = hasPermission()
            if (!currentPermission) {
                if (gpsRegistered || gnssRegistered) unregisterGps()
                lastFixUptime = 0L
                speedKmh = null
            } else if (!gpsRegistered) registerGps()
            settings = try {
                settingsRepository.read()
            } catch (e: Exception) {
                logErr("PhoneDashboard:settings", e)
                DashboardSettings()
            }
            batteryPct = battery()
            refreshEvents()
            ensureMap()
            maps.update(System.currentTimeMillis())
            view.invalidate()
            status.text = when {
                !currentPermission -> "Chưa cấp quyền GPS · xem bản đồ thủ công"
                !hasFreshGps() -> "Đang chờ tín hiệu GPS mới"
                else -> "GPS đã kết nối · ${speedKmh?.toString() ?: "--"} km/h"
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = runCatching { settingsRepository.read() }.getOrDefault(
            DashboardSettings()
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 17, 29))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun addTool(label: String, handler: () -> Unit) {
            toolbar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { handler() }
            }, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        addTool("← Trở lại") { finish() }
        addTool("8 preset") { choosePreset() }
        addTool("Sửa bố cục") {
            Toast.makeText(this, "Chỉnh sửa trong Trung tâm cấu hình.",
                Toast.LENGTH_SHORT).show()
            finish()
        }
        addTool("GPS của tôi") { maps.recenterMap() }
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "Dashboard cục bộ · không dùng để thao tác khi lái xe"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 12, 4)
        }
        root.addView(status)
        view = DashboardCanvasView()
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (!lifetime.start()) return
        maps.onFrame = {
            if (lifetime.active) view.postInvalidateOnAnimation()
        }
        currentPermission = hasPermission()
        registerGps()
        handler.post(ticker)
    }

    override fun onStop() {
        lifetime.stop()
        handler.removeCallbacks(ticker)
        unregisterGps()
        maps.onFrame = null
        maps.stop()
        speedKmh = null
        lastFixUptime = 0L
        super.onStop()
    }

    override fun onDestroy() {
        lifetime.destroy()
        handler.removeCallbacks(ticker)
        maps.dispose()
        super.onDestroy()
    }

    @Suppress("MissingPermission")
    private fun registerGps() {
        if (!lifetime.active || !currentPermission) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!gpsRegistered) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    1000L, 0f, gpsListener, Looper.getMainLooper())
                gpsRegistered = true
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    gpsListener.onLocationChanged(it)
                }
            }
            if (!gnssRegistered) {
                lm.registerGnssStatusCallback(mainExecutor, gnss)
                gnssRegistered = true
            }
        } catch (e: Exception) {
            logErr("PhoneDashboard:gpsRegister", e)
        }
    }

    private fun unregisterGps() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (gpsRegistered) lm.removeUpdates(gpsListener)
            if (gnssRegistered) lm.unregisterGnssStatusCallback(gnss)
        } catch (e: Exception) {
            logErr("PhoneDashboard:gpsStop", e)
        } finally {
            gpsRegistered = false
            gnssRegistered = false
            maps.setGnss(0, 0.0)
        }
    }

    private fun battery(): Int {
        val data = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = data.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = data.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun hasFreshGps(): Boolean =
        currentPermission && lastFixUptime > 0 &&
            SystemClock.uptimeMillis() - lastFixUptime in 0L..5_000L

    private fun refreshEvents() {
        val today = LocalDate.now()
        val raw = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getString("events", "").orEmpty()
        val key = today.toString() + raw
        if (key == lastEventsKey) return
        lastEventsKey = key
        eventLines = try { Events.lines(raw, today) } catch (e: Exception) {
            logErr("PhoneDashboard:events", e)
            emptyList()
        }
    }

    private fun ensureMap() {
        if (!lifetime.canRun(view.width, view.height)) return
        val b = PhoneDashboardGeometry.bounds(
            DashboardLayouts.active(settings), DashboardPanel.MAP,
            view.width, view.height
        ) ?: return
        if (b.width < 64f || b.height < 64f) return
        val w = b.width.toInt()
        val h = b.height.toInt()
        if (maps.running() && (abs(maps.frameW - w) >= 3 ||
                abs(maps.frameH - h) >= 3)) maps.stop()
        if (!maps.running()) maps.start(w, h)
    }

    private fun choosePreset() {
        val list = DashboardLayouts.presets
        val hasCustom = runCatching {
            settingsRepository.read().customLayout != null
        }.getOrDefault(false)
        val options = list.map { it.title }.toMutableList()
        if (hasCustom) options.add("Bố cục tùy chỉnh đã lưu")
        AlertDialog.Builder(this)
            .setTitle("Bố cục Dashboard trên điện thoại")
            .setItems(options.toTypedArray()) { _, which ->
                try {
                    settings = settingsRepository.change { current ->
                        if (which == list.size) current.copy(useCustomLayout = true)
                        else current.copy(
                            layoutPresetId = list[which].id,
                            useCustomLayout = false
                        )
                    }
                    view.invalidate()
                    ensureMap()
                } catch (e: Exception) {
                    logErr("PhoneDashboard:preset", e)
                    Toast.makeText(this, "Không thay đổi được bố cục.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private inner class DashboardCanvasView : View(this@PhoneDashboardActivity) {
        private val touchSlop = 13f * resources.displayMetrics.density
        private var touchOnMap = false
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private val scale = ScaleGestureDetector(
            this@PhoneDashboardActivity,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!touchOnMap) return false
                    val location = mapPoint(detector.focusX, detector.focusY)
                        ?: return false
                    maps.pinch(detector.scaleFactor,
                        location.first, location.second)
                    moved = true
                    return true
                }
            }
        )

        private fun mapPoint(x: Float, y: Float): Pair<Float, Float>? =
            PhoneDashboardGeometry.normalizedMapPoint(
                DashboardLayouts.active(settings), width, height, x, y
            )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val gpsGood = hasFreshGps()
            renderer.render(canvas, width, height, UiState(
                speedKmh = speedKmh.takeIf { gpsGood },
                gpsFix = gpsGood,
                satellites = if (gpsGood) maps.satUsed else 0,
                batteryPct = batteryPct,
                qrFile = qrFile,
                nowMs = System.currentTimeMillis(),
                selectedIcon = activeIcon,
                events = eventLines,
                mapFrame = maps.frame,
                mapFrameW = maps.frameW,
                mapFrameH = maps.frameH,
                mapsStatus = maps.status,
                streetName = maps.streetName.takeIf { gpsGood }.orEmpty(),
                signs = if (gpsGood) maps.signsState else SignsState.EMPTY,
                settings = settings
            ))
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!lifetime.active) return false
            scale.onTouchEvent(event)
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    touchOnMap = mapPoint(event.x, event.y) != null
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    moved = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchOnMap && !scale.isInProgress &&
                        event.pointerCount == 1) {
                        val dx = lastX - event.x
                        val dy = lastY - event.y
                        if (abs(event.x - startX) > touchSlop ||
                            abs(event.y - startY) > touchSlop) moved = true
                        if (moved && mapPoint(event.x, event.y) != null)
                            maps.scrollBy(dx, dy)
                    }
                    lastX = event.x
                    lastY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !scale.isInProgress) {
                        if (touchOnMap) {
                            mapPoint(event.x, event.y)?.let {
                                maps.tap(it.first, it.second)
                            }
                        } else {
                            val panel = PhoneDashboardGeometry.panelAt(
                                DashboardLayouts.active(settings),
                                width, height, event.x, event.y
                            )
                            if (panel == DashboardPanel.ICONS) {
                                activeIcon = renderer.hitIcon(event.x, event.y)
                                    .coerceAtLeast(0)
                                invalidate()
                            }
                        }
                    }
                    touchOnMap = false
                    performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchOnMap = false
                    true
                }
                else -> true
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
