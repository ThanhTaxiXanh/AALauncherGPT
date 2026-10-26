package Thanh.TaxiDriver.AALauncher.dashboard



import kotlin.math.min

/**
 * Pure geometry and lifecycle rules for the phone-only custom dashboard.
 * The car-host surface never uses these touch coordinates.
 */
internal object PhoneDashboardGeometry {
    data class Bounds(
        val left: Float, val top: Float, val right: Float, val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun contains(x: Float, y: Float) =
            x >= left && x <= right && y >= top && y <= bottom
    }

    fun bounds(
        layout: DashboardLayout, panel: DashboardPanel,
        viewWidth: Int, viewHeight: Int
    ): Bounds? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val p = layout.tile(panel)
        val pad = min(viewWidth, viewHeight) * 0.013f
        val usableW = viewWidth - 2f * pad
        val usableH = viewHeight - 2f * pad
        val inset = pad * 0.42f
        return Bounds(
            pad + p.x * usableW / 12f + inset,
            pad + p.y * usableH / 12f + inset,
            pad + (p.x + p.width) * usableW / 12f - inset,
            pad + (p.y + p.height) * usableH / 12f - inset
        )
    }

    fun panelAt(
        layout: DashboardLayout, viewWidth: Int, viewHeight: Int,
        x: Float, y: Float
    ): DashboardPanel? = if (!x.isFinite() || !y.isFinite()) null
    else DashboardPanel.entries.firstOrNull {
        bounds(layout, it, viewWidth, viewHeight)?.contains(x, y) == true
    }

    fun normalizedMapPoint(
        layout: DashboardLayout, viewWidth: Int, viewHeight: Int,
        x: Float, y: Float
    ): Pair<Float, Float>? {
        val b = bounds(layout, DashboardPanel.MAP, viewWidth, viewHeight)
            ?: return null
        if (!x.isFinite() || !y.isFinite() ||
            !b.contains(x, y) || b.width <= 0f || b.height <= 0f) return null
        return ((x - b.left) / b.width).coerceIn(0f, 1f) to
            ((y - b.top) / b.height).coerceIn(0f, 1f)
    }
}

internal class PhoneDashboardLifecycle {
    private var destroyed = false
    var active = false
        private set
    fun start(): Boolean {
        if (active || destroyed) return false
        active = true
        return true
    }
    fun stop(): Boolean {
        if (!active) return false
        active = false
        return true
    }
    fun destroy() { active = false; destroyed = true }
    fun canRun(width: Int, height: Int): Boolean =
        active && !destroyed && width >= 64 && height >= 64
}
