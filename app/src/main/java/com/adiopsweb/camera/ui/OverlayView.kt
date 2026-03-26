package com.adiopsweb.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.adiopsweb.camera.camera.AspectRatio
import com.adiopsweb.camera.camera.LensMode

/**
 * Transparent overlay that draws:
 *  - Rule-of-thirds grid
 *  - Tap-to-focus ring
 *  - Level indicator
 *  - Lens/focal-length info HUD
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var showGrid = false
        set(v) { field = v; invalidate() }

    var focusPoint: PointF? = null
        set(v) { field = v; invalidate() }

    var focusLocked = false
        set(v) { field = v; invalidate() }

    var levelAngle = 0f
        set(v) { field = v; invalidate() }

    var showLevel = true
        set(v) { field = v; invalidate() }

    var currentLens: LensMode = LensMode.WIDE
        set(v) { field = v; invalidate() }

    var aspectRatio: AspectRatio = AspectRatio.FULL
        set(v) { field = v; invalidate() }

    private val cropBarPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    // ── Paints ──────────────────────────────────────────────────────────────
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val lockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val levelCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showGrid) drawGrid(canvas)
        focusPoint?.let { drawFocusRing(canvas, it) }
        if (showLevel) drawLevel(canvas)
        drawHud(canvas)
        drawAspectRatioCrop(canvas)
    }

    private fun drawAspectRatioCrop(canvas: Canvas) {
        if (aspectRatio == AspectRatio.FULL) return
        val w = width.toFloat()
        val h = height.toFloat()
        val targetRatio = aspectRatio.w.toFloat() / aspectRatio.h.toFloat()
        val viewRatio = w / h
        if (targetRatio < viewRatio) {
            // Crop sides: view is too wide → draw bars on left/right
            val cropW = h * targetRatio
            val barW = (w - cropW) / 2f
            canvas.drawRect(0f, 0f, barW, h, cropBarPaint)
            canvas.drawRect(w - barW, 0f, w, h, cropBarPaint)
        } else {
            // Crop top/bottom
            val cropH = w / targetRatio
            val barH = (h - cropH) / 2f
            canvas.drawRect(0f, 0f, w, barH, cropBarPaint)
            canvas.drawRect(0f, h - barH, w, h, cropBarPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Vertical lines
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, gridPaint)
        // Horizontal lines
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, gridPaint)
    }

    private fun drawFocusRing(canvas: Canvas, pt: PointF) {
        val paint = if (focusLocked) lockedPaint else focusPaint
        val radius = 80f
        val crossLen = 20f

        canvas.drawCircle(pt.x, pt.y, radius, paint)
        // Corner ticks
        canvas.drawLine(pt.x - radius - crossLen, pt.y, pt.x - radius, pt.y, paint)
        canvas.drawLine(pt.x + radius, pt.y, pt.x + radius + crossLen, pt.y, paint)
        canvas.drawLine(pt.x, pt.y - radius - crossLen, pt.x, pt.y - radius, paint)
        canvas.drawLine(pt.x, pt.y + radius, pt.x, pt.y + radius + crossLen, paint)
    }

    private fun drawLevel(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f  // centre of screen, not behind the shutter button
        val lineHalf = 100f
        val threshold = 1.5f

        val isLevel = Math.abs(levelAngle) < threshold
        levelPaint.color = if (isLevel) Color.GREEN else Color.WHITE

        val rad = Math.toRadians(levelAngle.toDouble())
        val dx = (lineHalf * Math.cos(rad)).toFloat()
        val dy = (lineHalf * Math.sin(rad)).toFloat()

        canvas.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, levelPaint)

        // Center mark
        canvas.drawLine(cx - 8f, cy, cx + 8f, cy, levelCenterPaint)
    }

    private fun drawHud(canvas: Canvas) {
        val txt = "${currentLens.focalLengthMm}mm  ${currentLens.aperture}"
        canvas.drawText(txt, 32f, height - 80f, hudTextPaint)
    }
}
