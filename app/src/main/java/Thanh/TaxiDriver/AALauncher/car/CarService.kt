package Thanh.TaxiDriver.AALauncher.car

import Thanh.TaxiDriver.AALauncher.calendar.EventLine
import Thanh.TaxiDriver.AALauncher.calendar.Events
import Thanh.TaxiDriver.AALauncher.core.logErr
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardSettings
import Thanh.TaxiDriver.AALauncher.dashboard.DashboardSettingsRepository
import Thanh.TaxiDriver.AALauncher.dashboard.LauncherRenderer
import Thanh.TaxiDriver.AALauncher.dashboard.UiState
import Thanh.TaxiDriver.AALauncher.map.NativeMap
import Thanh.TaxiDriver.AALauncher.signs.SignsState

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

class CarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Do not allow arbitrary binders to control the release navigation service.
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session {
        val appCtx: Context = this
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen =
                LauncherScreen(carContext, appCtx)
        }
    }
}

/** Host-supported menu. No forms, unrestricted app launch or moving vehicle editing. */
internal class CarStatusMenu(
    ctx: CarContext,
    private val gpsStatus: CarGpsState,
    private val mapStatus: String,
    private val refreshMap: () -> Unit
) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val entries = ItemList.Builder()
            .addItem(Row.Builder()
                .setTitle("Trở lại bản đồ")
                .setOnClickListener {
                    carContext.getCarService(ScreenManager::class.java).pop()
                }.build())
            .addItem(Row.Builder()
                .setTitle("GPS trước menu: ${gpsStatus.title}")
                .build())
            .addItem(Row.Builder()
                .setTitle("Bản đồ: ${mapStatus.take(70)}")
                .build())
            .addItem(Row.Builder()
                .setTitle("Làm mới bản đồ")
                .setOnClickListener {
                    refreshMap()
                    carContext.getCarService(ScreenManager::class.java).pop()
                }.build())
            .addItem(Row.Builder()
                .setTitle("Cấu hình Dashboard trên điện thoại")
                .addText("Thao tác chỉnh sửa khi đã dừng xe")
                .build())
            .build()
        return ListTemplate.Builder()
            .setTitle("Trạng thái & điều khiển")
            .setHeaderAction(Action.BACK)
            .setSingleList(entries)
            .build()
    }
}

class LauncherScreen(ctx: CarContext, private val appCtx: Context) : Screen(ctx) {
    private val surfaceState = CarSurfaceState()
    private var surface: Surface? = null
    private var speedKmh: Int? = null
    private var lastFixMs = 0L
    private var selected = 0
    private var lastDraw = 0L
    private var evKey = ""
    private var evLines: List<EventLine> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appCtx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val configStore = DashboardSettingsRepository(appCtx)
    private var currentSettings = DashboardSettings()
    private val qrFile = File(appCtx.filesDir, "qr.png")
    private val maps = NativeMap(appCtx, handler)
    private val renderer = LauncherRenderer()
    private var gpsRegistered = false
    private var gnssRegistered = false
    private var locationPermission = false
    private var tickerRunning = false
    private var mapRefreshRequested = false

    private fun hasFineLocation(): Boolean =
        appCtx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun gpsState(): CarGpsState =
        CarGpsPolicy.state(surfaceState.canRun(), locationPermission,
            SystemClock.uptimeMillis(), lastFixMs)

    private val ticker = object : Runnable {
        override fun run() {
            if (!surfaceState.canRun()) {
                tickerRunning = false
                return
            }
            locationPermission = hasFineLocation()
            // A revoked permission immediately stops GPS callbacks.
            if (!locationPermission && (gpsRegistered || gnssRegistered)) {
                unregisterLocation()
                lastFixMs = 0L
                speedKmh = null
            } else if (locationPermission && !gpsRegistered) {
                registerLocation()
            }
            currentSettings = try {
                configStore.read()
            } catch (e: Exception) {
                logErr("CarService:dashboardSettings", e)
                DashboardSettings()
            }
            draw()
            if (mapRefreshRequested) {
                mapRefreshRequested = false
                maps.stop()
            }
            ensureMaps()
            maps.update(System.currentTimeMillis())
            handler.postDelayed(this, 1000L)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!surfaceState.canRun() || !locationPermission) return
            val ageNs = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (ageNs < 0L || ageNs > 10_000_000_000L) return
            lastFixMs = SystemClock.uptimeMillis()
            speedKmh = if (location.hasSpeed() && location.hasAccuracy() &&
                location.accuracy <= 35f && location.speed >= 0f) {
                if (location.speed < 0.6f) 0
                else (location.speed * 3.6f).roundToInt()
            } else null
            maps.onLocation(location)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (!surfaceState.canRun()) return
            var used = 0
            var sum = 0.0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    sum += status.getCn0DbHz(i)
                }
            }
            maps.setGnss(used, if (used > 0) sum / used else 0.0)
        }
    }

    init {
        maps.onFrame = {
            if (surfaceState.canRun() &&
                SystemClock.uptimeMillis() - lastDraw >= 40L) draw()
        }
        // Screen may STOP while a menu/another app is on top even if Surface
        // stays allocated. Suspend GPS, map VirtualDisplay and ticker then.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    surfaceState.onStart()
                    startUpdates()
                }
                Lifecycle.Event.ON_STOP -> {
                    surfaceState.onStop()
                    stopUpdates()
                    maps.stop()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    surfaceState.onDestroy()
                    stopUpdates()
                    maps.dispose()
                    surface?.release()
                    surface = null
                }
                else -> Unit
            }
        })
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(
            object : SurfaceCallback {
                override fun onSurfaceAvailable(container: SurfaceContainer) {
                    val candidate = container.surface ?: return
                    val oldWidth = surfaceState.width
                    val oldHeight = surfaceState.height
                    if (!surfaceState.onAvailable(
                            candidate, container.width, container.height
                        )) {
                        if (candidate !== surface) candidate.release()
                        return
                    }
                    val previous = surface
                    if (previous != null && previous !== candidate) {
                        stopUpdates()
                        maps.stop()
                        previous.release()
                    } else if (previous != null &&
                        (oldWidth != container.width || oldHeight != container.height)
                    ) {
                        // Resize without dropping the actual host surface.
                        maps.stop()
                    }
                    surface = candidate
                    startUpdates()
                }

                override fun onSurfaceDestroyed(container: SurfaceContainer) {
                    if (!surfaceState.onDestroyed(container.surface)) return
                    stopUpdates()
                    maps.stop()
                    surface?.release()
                    surface = null
                }

                override fun onClick(x: Float, y: Float) {
                    if (surfaceState.canRun()) handleClick(x, y)
                }

                override fun onScroll(distanceX: Float, distanceY: Float) {
                    if (surfaceState.canRun()) maps.scrollBy(distanceX, distanceY)
                }

                override fun onFling(velocityX: Float, velocityY: Float) {
                    if (surfaceState.canRun()) maps.fling(velocityX, velocityY)
                }

                override fun onScale(
                    focusX: Float, focusY: Float, scaleFactor: Float
                ) {
                    if (!surfaceState.canRun()) return
                    val p = renderer.hitMapPoint(focusX, focusY)
                    if (p != null) maps.pinch(scaleFactor, p[0], p[1])
                }
            }
        )
    }

    private fun handleClick(x: Float, y: Float) {
        val i = renderer.hitIcon(x, y)
        if (i >= 0) {
            selected = i
            draw()
            return
        }
        renderer.hitMapPoint(x, y)?.let { maps.tap(it[0], it[1]) }
    }

    private fun battery(): Int {
        val i = appCtx.registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun eventLines(): List<EventLine> {
        val raw = prefs.getString("events", "") ?: ""
        val today = LocalDate.now()
        val key = raw + today.toString()
        if (key != evKey) {
            evKey = key
            evLines = try {
                Events.lines(raw, today)
            } catch (e: Exception) {
                emptyList()
            }
        }
        return evLines
    }

    private fun ensureMaps() {
        if (!surfaceState.canRun()) return
        val r = renderer.mapRect() ?: return
        if (r.width() < 64f || r.height() < 64f) return
        val wantW = r.width().toInt()
        val wantH = r.height().toInt()
        if (maps.running() &&
            (kotlin.math.abs(maps.frameW - wantW) >= 3 ||
                kotlin.math.abs(maps.frameH - wantH) >= 3)) {
            maps.stop()
        }
        if (!maps.running()) maps.start(wantW, wantH)
    }

    private fun startUpdates() {
        if (!surfaceState.canRun()) return
        locationPermission = hasFineLocation()
        if (!tickerRunning) {
            tickerRunning = true
            handler.post(ticker)
        }
        registerLocation()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocation() {
        if (!surfaceState.canRun() || !locationPermission) return
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!gpsRegistered) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L,
                    0f, locListener, Looper.getMainLooper())
                gpsRegistered = true
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    locListener.onLocationChanged(it)
                }
            }
            if (!gnssRegistered) {
                lm.registerGnssStatusCallback(appCtx.mainExecutor, gnssCallback)
                gnssRegistered = true
            }
        } catch (e: Exception) {
            logErr("CarService:registerLocation", e)
        }
    }

    private fun unregisterLocation() {
        try {
            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (gpsRegistered) lm.removeUpdates(locListener)
            if (gnssRegistered) lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: Exception) {
            logErr("CarService:unregisterLocation", e)
        } finally {
            gpsRegistered = false
            gnssRegistered = false
            maps.setGnss(0, 0.0)
        }
    }

    private fun stopUpdates() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
        unregisterLocation()
        lastFixMs = 0L
        speedKmh = null
    }

    private fun requestMapRefresh() { mapRefreshRequested = true }

    private fun draw() {
        if (!surfaceState.canRun()) return
        val s = surface ?: return
        if (!s.isValid) return
        lastDraw = SystemClock.uptimeMillis()
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            logErr("CarService:lockCanvas", e)
            return
        }
        try {
            val gpsReady = gpsState() == CarGpsState.READY
            val st = UiState(
                speedKmh = speedKmh.takeIf { gpsReady },
                gpsFix = gpsReady,
                satellites = maps.satUsed,
                batteryPct = battery(),
                qrFile = qrFile,
                nowMs = System.currentTimeMillis(),
                selectedIcon = selected,
                events = eventLines(),
                mapFrame = maps.frame,
                mapFrameW = maps.frameW,
                mapFrameH = maps.frameH,
                mapsStatus = maps.status,
                streetName = maps.streetName.takeIf { gpsReady } ?: "",
                signs = if (gpsReady) maps.signsState else SignsState.EMPTY,
                settings = currentSettings
            )
            renderer.render(canvas, surfaceState.width,
                surfaceState.height, st)
        } catch (e: Exception) {
            logErr("CarService:draw", e)
        } finally {
            try { s.unlockCanvasAndPost(canvas) } catch (e: Exception) {
                logErr("CarService:unlockCanvas", e)
            }
        }
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Menu")
                            .setOnClickListener {
                                carContext.getCarService(ScreenManager::class.java)
                                    .push(CarStatusMenu(
                                        carContext,
                                        gpsStatus = gpsState(),
                                        mapStatus = maps.status,
                                        refreshMap = { requestMapRefresh() }
                                    ))
                            }
                            .build()
                    ).build()
            ).build()
}
