package com.cartaxi.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Phone-only editable grid preview. Tap a tile to select. Drag to move;
 * drag its bottom-right corner to resize. Invalid placements are rejected
 * before changing the saved/preview layout; left icon rail is immovable.
 */
class DashboardLayoutPreview(context: Context) : View(context) {
    var dashboardLayout: DashboardLayout = DashboardLayouts.presets.first().layout
        private set
    var selectedPanel: DashboardPanel = DashboardPanel.MAP
        private set
    var onEdited: (DashboardLayout) -> Unit = {}
    var onSelectionChanged: (DashboardPanel) -> Unit = {}
    var onRejected: (String) -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var downX = 0f
    private var downY = 0f
    private var downTile: DashboardTile? = null
    private var downLayout: DashboardLayout? = null
    private var changedOnDrag = false
    private var resizing = false

    fun display(value: DashboardLayout) {
        dashboardLayout = value
        invalidate()
    }

    private fun bounds(tile: DashboardTile): RectF {
        val dx = width / 12f
        val dy = height / 12f
        val pad = resources.displayMetrics.density * 2.2f
        return RectF(
            tile.x * dx + pad, tile.y * dy + pad,
            (tile.x + tile.width) * dx - pad,
            (tile.y + tile.height) * dy - pad
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 20, 35))
        for (tile in dashboardLayout.tiles) {
            val rect = bounds(tile)
            paint.style = Paint.Style.FILL
            paint.color = when (tile.panel) {
                DashboardPanel.ICONS -> Color.rgb(56, 66, 91)
                DashboardPanel.MAP -> Color.rgb(33, 94, 132)
                DashboardPanel.CALENDAR -> Color.rgb(79, 87, 140)
                DashboardPanel.QR -> Color.rgb(105, 78, 128)
                DashboardPanel.SIGNS -> Color.rgb(111, 81, 66)
            }
            canvas.drawRoundRect(rect, 8f, 8f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (tile.panel == selectedPanel) 5f else 1.5f
            paint.color = if (tile.panel == selectedPanel) Color.YELLOW else Color.LTGRAY
            canvas.drawRoundRect(rect, 8f, 8f, paint)
            val label = if (tile.panel == DashboardPanel.ICONS) "≡" else tile.panel.title
            val usable = rect.width() - 6f
            letters.textSize = minOf(
                resources.displayMetrics.scaledDensity * 13f,
                (usable / (label.length.coerceAtLeast(2) * 0.55f))
            ).coerceAtLeast(7f)
            canvas.drawText(label, rect.centerX(),
                rect.centerY() - (letters.ascent() + letters.descent()) / 2, letters)
            if (tile.panel == selectedPanel && tile.panel != DashboardPanel.ICONS) {
                paint.style = Paint.Style.FILL
                paint.color = Color.YELLOW
                canvas.drawCircle(rect.right - 4f, rect.bottom - 4f, 6f, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val found = dashboardLayout.tiles.reversed().firstOrNull {
                    bounds(it).contains(event.x, event.y)
                } ?: return false
                selectedPanel = found.panel
                downTile = if (found.panel == DashboardPanel.ICONS) null else found
                downX = event.x
                downY = event.y
                downLayout = dashboardLayout
                changedOnDrag = false
                parent?.requestDisallowInterceptTouchEvent(true)
                val rect = bounds(found)
                val threshold = resources.displayMetrics.density * 22f
                resizing = found.panel != DashboardPanel.ICONS &&
                    event.x >= rect.right - threshold &&
                    event.y >= rect.bottom - threshold
                onSelectionChanged(selectedPanel)
                invalidate()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = downTile ?: return true
                val deltaX = ((event.x - downX) * 12f / width).roundToInt()
                val deltaY = ((event.y - downY) * 12f / height).roundToInt()
                val source = downLayout ?: return true
                val candidate = try {
                    if (resizing) source.edit(
                        start.panel, start.x, start.y,
                        start.width + deltaX, start.height + deltaY
                    ) else source.edit(
                        start.panel, start.x + deltaX, start.y + deltaY,
                        start.width, start.height
                    )
                } catch (_: IllegalArgumentException) { null }
                if (candidate != null && candidate != dashboardLayout) {
                    dashboardLayout = candidate
                    changedOnDrag = true
                    onEdited(candidate)
                    invalidate()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (downTile != null && !changedOnDrag &&
                    (kotlin.math.abs(event.x - downX) > 12f ||
                        kotlin.math.abs(event.y - downY) > 12f)) {
                    onRejected("Không thể đặt ô vào vị trí này.")
                }
                downTile = null
                downLayout = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                downTile = null
                downLayout = null
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
