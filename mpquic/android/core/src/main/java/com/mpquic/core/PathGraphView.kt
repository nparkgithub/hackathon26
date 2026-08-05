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

    /** Feed one stats tick: map of path key -> packets sent in the interval. */
    fun addSamples(samples: Map<String, Float>) {
        val now = SystemClock.elapsedRealtime()
        for ((key, value) in samples) {
            val s = series.getOrPut(key) { Series(PALETTE[series.size % PALETTE.size]) }
            s.points.addLast(now to value)
        }
        val cutoff = now - WINDOW_MS
        for (s in series.values) {
            while (s.points.isNotEmpty() && s.points.first().first < cutoff) {
                s.points.removeFirst()
            }
        }
        series.entries.removeAll { it.value.points.isEmpty() }
        invalidate()
    }

    fun clear() {
        series.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.elapsedRealtime()
        val t0 = now - WINDOW_MS

        var maxY = 10f
        for (s in series.values) {
            for (p in s.points) if (p.second > maxY) maxY = p.second
        }

        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        textPaint.color = 0xFF888888.toInt()
        canvas.drawText("${maxY.toInt()} pkts/s", 8f, 26f, textPaint)
        canvas.drawText("60s", w - 60f, h - 8f, textPaint)

        var legendY = 26f
        for ((key, s) in series) {
            linePaint.color = s.color
            var prevX = 0f
            var prevY = 0f
            var first = true
            for ((t, v) in s.points) {
                val x = (t - t0) / WINDOW_MS.toFloat() * w
                val y = h - (v / maxY) * (h - 40f) - 6f
                if (!first) canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x
                prevY = y
                first = false
            }
            legendY += 28f
            textPaint.color = s.color
            canvas.drawText(key, 8f, legendY, textPaint)
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
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
