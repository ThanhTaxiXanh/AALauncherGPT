package Thanh.TaxiDriver.AALauncher.map



/** Camera modes supported by the current map surface; route overview needs route data. */
internal enum class DrivingCameraMode {
    FOLLOW_2D,
    FOLLOW_3D,
    FREE_PAN
}

/**
 * Small lifecycle-independent state machine for Android Auto map gestures.
 * No Android, MapLibre or Activity references are held here.
 */
internal class DrivingCameraController(
    private val panTimeoutMs: Long = 12_000L,
    private val zoomHoldMs: Long = 15_000L
) {
    init {
        require(panTimeoutMs > 0L)
        require(zoomHoldMs > 0L)
    }

    var mode: DrivingCameraMode = DrivingCameraMode.FOLLOW_3D
        private set

    private var preferredFollowMode = DrivingCameraMode.FOLLOW_3D
    private var lastGestureMs = 0L
    private var lastScaleMs: Long? = null
    private var zoomValue: Double? = null
    private var zoomChangedMs = 0L

    var swipeRotateMode: Boolean = false
        private set

    val following: Boolean
        get() = mode != DrivingCameraMode.FREE_PAN

    val tiltDegrees: Double
        get() = if (preferredFollowMode == DrivingCameraMode.FOLLOW_3D) 60.0 else 0.0

    fun onSurfaceStarted() {
        mode = preferredFollowMode
        swipeRotateMode = false
        lastScaleMs = null
        zoomValue = null
    }

    fun leaveFollow(nowMs: Long) {
        mode = DrivingCameraMode.FREE_PAN
        lastGestureMs = nowMs
    }

    fun touched(nowMs: Long) {
        lastGestureMs = nowMs
    }

    fun scaled(nowMs: Long) {
        lastScaleMs = nowMs
        if (!following) touched(nowMs)
    }

    fun canScroll(nowMs: Long): Boolean =
        lastScaleMs?.let { nowMs - it >= 350L } ?: true

    fun canFling(nowMs: Long): Boolean =
        !swipeRotateMode && (lastScaleMs?.let { nowMs - it >= 600L } ?: true)

    fun setTemporaryZoom(value: Double, nowMs: Long) {
        zoomValue = value.coerceIn(3.0, 20.0)
        zoomChangedMs = nowMs
    }

    fun temporaryZoom(nowMs: Long): Double? {
        if (zoomValue != null && nowMs - zoomChangedMs > zoomHoldMs) {
            zoomValue = null
        }
        return zoomValue
    }

    fun toggleTilt() {
        preferredFollowMode = if (preferredFollowMode == DrivingCameraMode.FOLLOW_3D)
            DrivingCameraMode.FOLLOW_2D else DrivingCameraMode.FOLLOW_3D
        if (following) mode = preferredFollowMode
    }

    fun toggleSwipeRotate(nowMs: Long) {
        swipeRotateMode = !swipeRotateMode
        if (swipeRotateMode) leaveFollow(nowMs) else touched(nowMs)
    }

    fun shouldRecenter(nowMs: Long): Boolean =
        !following && nowMs - lastGestureMs > panTimeoutMs

    fun recenter() {
        mode = preferredFollowMode
        swipeRotateMode = false
        zoomValue = null
    }
}
