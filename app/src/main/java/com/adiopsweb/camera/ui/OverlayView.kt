package com.adiopsweb.camera.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.adiopsweb.camera.camera.AspectRatio
import com.adiopsweb.camera.camera.GridType
import com.adiopsweb.camera.camera.LensMode
import kotlin.math.abs

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var gridType: GridType = GridType.OFF
        set(v) { field = v; invalidate() }

    var focusPoint: PointF? = null
        set(v) { field = v; invalidate() }

    var focusLocked = false
        set(v) { field = v; invalidate() }

    var levelAngle = 0f
        set(v) { field = v; invalidate() }

    var showLevel = true
        set(v) { field = v; invalidate() }

    var showHistogram = false
        set(v) { field = v; invalidate() }

    var currentLens: LensMode = LensMode.WIDE
        set(v) { field = v; invalidate() }

    var aspectRatio: AspectRatio = AspectRatio.FULL
        set(v) { field = v; invalidate() }

    // Feed real brightness histogram data (0–255 bins, normalised 0f–1f)
    var histogramData: FloatArray? = null
        set(v) { field = v; if (showHistogram) invalidate() }

    // ── Paints ───────────────────────────────────────────────────────────────

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val goldenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 215, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val lockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val levelCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val cropPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.FILL
    }
    private val histBgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }
    private val histBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawAspectRatioCrop(canvas)
        drawGrid(canvas)
        focusPoint?.let { drawFocusRing(canvas, it) }
        if (showLevel) drawLevel(canvas)
        drawHud(canvas)
        if (showHistogram) drawHistogram(canvas)
    }

    // ── Grid ─────────────────────────────────────────────────────────────────

    private fun drawGrid(canvas: Canvas) {
        when (gridType) {
            GridType.OFF    -> Unit
            GridType.THIRDS -> drawThirdsGrid(canvas)
            GridType.GOLDEN -> drawGoldenGrid(canvas)
            GridType.SQUARE -> drawSquareGrid(canvas)
        }
    }

    private fun drawThirdsGrid(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawLine(w / 3, 0f, w / 3, h, gridPaint)
        canvas.drawLine(2 * w / 3, 0f, 2 * w / 3, h, gridPaint)
        canvas.drawLine(0f, h / 3, w, h / 3, gridPaint)
        canvas.drawLine(0f, 2 * h / 3, w, 2 * h / 3, gridPaint)
    }

    private fun drawSquareGrid(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..8) {
            canvas.drawLine(w * i / 9, 0f, w * i / 9, h, gridPaint)
            canvas.drawLine(0f, h * i / 9, w, h * i / 9, gridPaint)
        }
    }

    /**
     * Golden ratio grid: Fibonacci-based spiral construction lines.
     * Draws a phi-rectangle subdivision and spiral arc approximation.
     */
    private fun drawGoldenGrid(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val phi = 1.6180339f

        // Vertical dividers at 1/phi and (1 - 1/phi)
        val gV = w / phi
        canvas.drawLine(gV, 0f, gV, h, goldenPaint)
        canvas.drawLine(w - gV, 0f, w - gV, h, goldenPaint)
        // Horizontal dividers
        val gH = h / phi
        canvas.drawLine(0f, gH, w, gH, goldenPaint)
        canvas.drawLine(0f, h - gH, w, h - gH, goldenPaint)

        // Spiral arc in top-left quadrant (decorative approximation)
        val path = Path()
        val arcRect = RectF(0f, 0f, gV * 2, gH * 2)
        path.addArc(arcRect, 180f, 90f)
        val arcRect2 = RectF(gV - gH, 0f, gV, gH)
        path.addArc(arcRect2, 270f, 90f)
        canvas.drawPath(path, goldenPaint)
    }

    // ── Focus ring ────────────────────────────────────────────────────────────

    private fun drawFocusRing(canvas: Canvas, pt: PointF) {
        val paint = if (focusLocked) lockedPaint else focusPaint
        val r = 80f; val tick = 22f
        canvas.drawCircle(pt.x, pt.y, r, paint)
        // Corner ticks
        canvas.drawLine(pt.x - r - tick, pt.y, pt.x - r, pt.y, paint)
        canvas.drawLine(pt.x + r, pt.y, pt.x + r + tick, pt.y, paint)
        canvas.drawLine(pt.x, pt.y - r - tick, pt.x, pt.y - r, paint)
        canvas.drawLine(pt.x, pt.y + r, pt.x, pt.y + r + tick, paint)
    }

    // ── Level ─────────────────────────────────────────────────────────────────

    private fun drawLevel(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val halfLen = 110f; val threshold = 1.5f
        levelPaint.color = if (abs(levelAngle) < threshold) Color.GREEN else Color.WHITE
        val rad = Math.toRadians(levelAngle.toDouble())
        val dx = (halfLen * Math.cos(rad)).toFloat()
        val dy = (halfLen * Math.sin(rad)).toFloat()
        canvas.drawLine(cx - dx, cy + dy, cx + dx, cy - dy, levelPaint)
        canvas.drawLine(cx - 10f, cy, cx + 10f, cy, levelCenterPaint)
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    private fun drawHud(canvas: Canvas) {
        val txt = "${currentLens.focalLengthMm}mm  ${currentLens.aperture}"
        canvas.drawText(txt, 32f, height - 90f, hudTextPaint)
    }

    // ── Aspect-ratio crop bars ────────────────────────────────────────────────

    private fun drawAspectRatioCrop(canvas: Canvas) {
        if (aspectRatio == AspectRatio.FULL) return
        val w = width.toFloat(); val h = height.toFloat()
        val targetRatio = aspectRatio.w.toFloat() / aspectRatio.h.toFloat()
        val viewRatio = w / h
        if (targetRatio < viewRatio) {
            val barW = (w - h * targetRatio) / 2f
            canvas.drawRect(0f, 0f, barW, h, cropPaint)
            canvas.drawRect(w - barW, 0f, w, h, cropPaint)
        } else {
            val barH = (h - w / targetRatio) / 2f
            canvas.drawRect(0f, 0f, w, barH, cropPaint)
            canvas.drawRect(0f, h - barH, w, h, cropPaint)
        }
    }

    // ── Histogram ─────────────────────────────────────────────────────────────

    private fun drawHistogram(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val histW = 200f; val histH = 80f
        val left = w - histW - 16f; val top = h - histH - 96f

        // Background
        canvas.drawRect(left, top, left + histW, top + histH, histBgPaint)

        val data = histogramData
        if (data != null && data.isNotEmpty()) {
            val barW = histW / data.size
            for (i in data.indices) {
                val barH2 = data[i].coerceIn(0f, 1f) * histH
                val x = left + i * barW
                canvas.drawRect(x, top + histH - barH2, x + barW - 0.5f, top + histH, histBarPaint)
            }
        } else {
            // Draw a placeholder bell-curve shape
            val bins = 32
            val barW2 = histW / bins
            for (i in 0 until bins) {
                val t = (i - bins / 2f) / (bins / 4f)
                val barH2 = (Math.exp((-0.5 * t * t).toDouble()) * histH).toFloat()
                val x = left + i * barW2
                canvas.drawRect(x, top + histH - barH2, x + barW2 - 0.5f, top + histH, histBarPaint)
            }
        }

        // Label
        hudTextPaint.textSize = 18f
        canvas.drawText("HIST", left + 4f, top + 16f, hudTextPaint)
        hudTextPaint.textSize = 30f
    }
}
