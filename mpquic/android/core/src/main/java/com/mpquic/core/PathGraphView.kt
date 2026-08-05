package com.mpquic.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Rolling line graph of per-path activity: X = time (last 60 s), Y = packets
 * sent per stats interval (~1 s). One series per tquic path, colored from a
 * fixed palette in order of appearance — a single path draws one line,
 * multipath draws one color per path. Legend shows each path's 4-tuple.
 *
 * The view refreshes itself every 2 s while attached, so the time window
 * keeps sliding even between stats ticks; new samples also redraw
 * immediately. Axis ranges are labeled: Y max and midpoint (pkts/s, auto-
 * scaled) and the X range from -60 s to now.
 */
class PathGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Series(val color: Int) {
        val points = ArrayDeque<Pair<Long, Float>>()
    }

    private val series = LinkedHashMap<String, Series>()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
    }
    private val gridPaint = Paint().apply {
        color = 0x33888888
        strokeWidth = 1f
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, REFRESH_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshTick)
        super.onDetachedFromWindow()
    }

    /** Feed one stats tick: map of path key -> packets sent in the interval. */
    fun addSamples(samples: Map<String, Float>) {
        val now = SystemClock.elapsedRealtime()
        for ((key, value) in samples) {
            val s = series.getOrPut(key) { Series(PALETTE[series.size % PALETTE.size]) }
            s.points.addLast(now to value)
        }
        trim(now)
        invalidate()
    }

    fun clear() {
        series.clear()
        invalidate()
    }

    private fun trim(now: Long) {
        val cutoff = now - WINDOW_MS
        for (s in series.values) {
            while (s.points.isNotEmpty() && s.points.first().first < cutoff) {
                s.points.removeFirst()
            }
        }
        series.entries.removeAll { it.value.points.isEmpty() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.elapsedRealtime()
        trim(now)
        val t0 = now - WINDOW_MS

        var maxY = 10f
        for (s in series.values) {
            for (p in s.points) if (p.second > maxY) maxY = p.second
        }

        // Plot area: leave room for the legend on top and x labels below.
        val top = 34f
        val bottom = h - X_LABEL_BAND
        fun yFor(v: Float) = bottom - (v / maxY) * (bottom - top)
        fun xFor(t: Long) = (t - t0) / WINDOW_MS.toFloat() * w

        // Gridlines at max, mid and zero, with y-range labels.
        textPaint.color = 0xFF888888.toInt()
        for (frac in floatArrayOf(1f, 0.5f, 0f)) {
            val y = yFor(maxY * frac)
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = "${(maxY * frac).toInt()}"
            canvas.drawText(label, 8f, y - 6f, textPaint)
        }
        canvas.drawText("pkts/s", 8f, yFor(maxY) + 24f, textPaint)

        // X range labels: -60 s .. now.
        val xLabelY = h - 8f
        canvas.drawText("-60s", 8f, xLabelY, textPaint)
        canvas.drawText("-30s", w / 2f - 30f, xLabelY, textPaint)
        canvas.drawText("now", w - 70f, xLabelY, textPaint)

        var legendY = 26f
        for ((key, s) in series) {
            linePaint.color = s.color
            var prevX = 0f
            var prevY = 0f
            var first = true
            for ((t, v) in s.points) {
                val x = xFor(t)
                val y = yFor(v)
                if (!first) canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x
                prevY = y
                first = false
            }
            textPaint.color = s.color
            canvas.drawText(key, 110f, legendY, textPaint)
            legendY += 28f
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val REFRESH_MS = 2_000L
        const val X_LABEL_BAND = 34f
        val PALETTE = intArrayOf(
            0xFF1E88E5.toInt(), // blue
            0xFFE53935.toInt(), // red
            0xFF43A047.toInt(), // green
            0xFFFB8C00.toInt(), // orange
            0xFF8E24AA.toInt(), // purple
            0xFF00ACC1.toInt(), // cyan
        )
    }
}
