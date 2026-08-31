package com.lsfg.android.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View

/**
 * Scrolling three-series line graph showing instantaneous frame rate:
 *   - Real captured frames (green)
 *   - LSFG-generated frames (orange)
 *   - Total output frames (cyan)
 *
 * Samples are fed at ~5 Hz via [pushSample] and the newest sample appears on the right edge.
 * The y-axis auto-scales to the max fps seen in the window.
 */
class FrameGraphView(ctx: Context) : View(ctx) {

    private val historySize = 50  // 10 s at 5 Hz
    private val realSeries = FloatArray(historySize)
    private val genSeries = FloatArray(historySize)
    private val totalSeries = FloatArray(historySize)
    private var head = 0
    private var filled = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB0000000.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FFFFFF
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA0FFFFFF.toInt()
        textSize = sp(10f)
    }

    // Real captures: Green
    private val realPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34D399.toInt() // green
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val realFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1534D399
        style = Paint.Style.FILL
    }

    // LSFG Generated frames: Orange/Amber
    private val genPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFB923C.toInt() // orange/amber
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val genFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x15FB923C
        style = Paint.Style.FILL
    }

    // Total displayed frames: Cyan/Blue
    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF60A5FA.toInt() // cyan
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val totalFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1560A5FA
        style = Paint.Style.FILL
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(10f)
    }

    private val path = Path()
    private val fillPath = Path()

    fun pushSample(realFps: Float, generatedFps: Float, totalFps: Float) {
        realSeries[head] = realFps.coerceAtLeast(0f)
        genSeries[head] = generatedFps.coerceAtLeast(0f)
        totalSeries[head] = totalFps.coerceAtLeast(0f)
        head = (head + 1) % historySize
        if (filled < historySize) filled++
        postInvalidateOnAnimation()
    }

    fun reset() {
        head = 0
        filled = 0
        for (i in 0 until historySize) {
            realSeries[i] = 0f
            genSeries[i] = 0f
            totalSeries[i] = 0f
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, dp(6f), dp(6f), bgPaint)

        val padL = dp(28f)
        val padR = dp(6f)
        val padT = dp(14f)
        val padB = dp(14f)
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        // Determine max y from samples with a floor.
        var maxY = 30f
        val n = filled
        for (i in 0 until n) {
            val r = realSeries[i]
            val g = genSeries[i]
            val t = totalSeries[i]
            if (r > maxY) maxY = r
            if (g > maxY) maxY = g
            if (t > maxY) maxY = t
        }
        // Round the scale up to the next nice bucket.
        val niceBuckets = floatArrayOf(30f, 60f, 90f, 120f, 144f, 180f, 240f)
        for (b in niceBuckets) {
            if (maxY <= b) { maxY = b; break }
        }

        // Gridlines at 1/2 and max.
        canvas.drawLine(padL, padT, padL + plotW, padT, gridPaint)
        canvas.drawLine(padL, padT + plotH / 2f, padL + plotW, padT + plotH / 2f, gridPaint)
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, gridPaint)
        canvas.drawText("${maxY.toInt()}", dp(4f), padT + sp(4f), axisPaint)
        canvas.drawText("${(maxY / 2).toInt()}", dp(4f), padT + plotH / 2f + sp(4f), axisPaint)
        canvas.drawText("0", dp(4f), padT + plotH + sp(4f), axisPaint)

        if (n < 2) {
            drawLegend(canvas, padL, h)
            return
        }

        val stepX = plotW / (historySize - 1).toFloat()

        fun drawSeries(series: FloatArray, stroke: Paint, fill: Paint) {
            path.rewind()
            val start = if (filled < historySize) 0 else head
            val count = filled
            val firstX = padL + (historySize - count) * stepX
            for (i in 0 until count) {
                val idx = (start + i) % historySize
                val v = series[idx]
                val x = firstX + i * stepX
                val y = padT + plotH - (v / maxY) * plotH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            fillPath.rewind()
            fillPath.addPath(path)
            fillPath.lineTo(padL + plotW, padT + plotH)
            fillPath.lineTo(padL + (historySize - count) * stepX, padT + plotH)
            fillPath.close()
            canvas.drawPath(fillPath, fill)
            canvas.drawPath(path, stroke)
        }

        drawSeries(realSeries, realPaint, realFillPaint)
        drawSeries(genSeries, genPaint, genFillPaint)
        drawSeries(totalSeries, totalPaint, totalFillPaint)

        drawLegend(canvas, padL, h)
    }

    private fun drawLegend(canvas: Canvas, padL: Float, h: Float) {
        val y = h - dp(4f)
        val swatchW = dp(10f)
        val swatchH = dp(3f)
        var x = padL

        // Real: Green
        canvas.drawRect(x, y - swatchH, x + swatchW, y, realPaint)
        canvas.drawText("real", x + swatchW + dp(3f), y, legendPaint)
        x += swatchW + dp(3f) + legendPaint.measureText("real") + dp(10f)

        // Generated: Orange
        canvas.drawRect(x, y - swatchH, x + swatchW, y, genPaint)
        canvas.drawText("generated", x + swatchW + dp(3f), y, legendPaint)
        x += swatchW + dp(3f) + legendPaint.measureText("generated") + dp(10f)

        // Total: Cyan
        canvas.drawRect(x, y - swatchH, x + swatchW, y, totalPaint)
        canvas.drawText("total", x + swatchW + dp(3f), y, legendPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
