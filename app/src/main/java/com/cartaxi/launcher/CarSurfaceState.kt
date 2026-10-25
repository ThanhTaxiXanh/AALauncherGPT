package com.cartaxi.launcher

/**
 * Host-surface lifecycle gate, independent of Android so it can be replayed
 * in deterministic JVM tests. Surface instances are compared by identity.
 */
internal class CarSurfaceState {
    private var attached: Any? = null
    private var started = false
    private var destroyed = false
    var width = 0
        private set
    var height = 0
        private set

    fun onStart() { if (!destroyed) started = true }
    fun onStop() { started = false }

    /** Returns false for invalid data; caller retains the previous surface. */
    fun onAvailable(token: Any?, w: Int, h: Int): Boolean {
        if (destroyed || token == null || w < 64 || h < 64 ||
            w > 8192 || h > 8192) return false
        attached = token
        width = w
        height = h
        return true
    }

    /** Ignore late onSurfaceDestroyed callbacks for an older surface. */
    fun onDestroyed(token: Any?): Boolean {
        if (attached !== token || token == null) return false
        attached = null
        width = 0
        height = 0
        return true
    }

    fun onDestroy() {
        destroyed = true
        started = false
        attached = null
        width = 0
        height = 0
    }

    fun canRun(): Boolean = started && !destroyed && attached != null &&
        width >= 64 && height >= 64

    fun owns(token: Any?): Boolean = attached != null && attached === token
}

internal enum class CarGpsState(val title: String) {
    SURFACE_WAIT("Chưa có màn hình"),
    PERMISSION_REQUIRED("Cần cấp quyền vị trí trên điện thoại"),
    WAITING("Đang chờ tín hiệu GPS"),
    STALE("Tín hiệu GPS đã cũ"),
    READY("Đã nhận GPS")
}

/** Read-only diagnostics; a stale fix is never displayed as a current speed. */
internal object CarGpsPolicy {
    const val MAX_FIX_AGE_MS = 5_000L

    fun state(
        hasSurface: Boolean, permissionGranted: Boolean,
        nowUptimeMs: Long, lastFixUptimeMs: Long
    ): CarGpsState = when {
        !hasSurface -> CarGpsState.SURFACE_WAIT
        !permissionGranted -> CarGpsState.PERMISSION_REQUIRED
        lastFixUptimeMs <= 0L -> CarGpsState.WAITING
        nowUptimeMs < lastFixUptimeMs -> CarGpsState.STALE
        nowUptimeMs - lastFixUptimeMs > MAX_FIX_AGE_MS -> CarGpsState.STALE
        else -> CarGpsState.READY
    }
}
