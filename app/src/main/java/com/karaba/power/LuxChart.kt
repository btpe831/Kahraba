package com.karaba.power

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/** A simple live line chart of light readings (lux over time), in the theme's colours. */
class LuxChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** How many seconds of readings to show. */
    var windowSeconds = 60
        set(value) { field = value; invalidate() }

    private var times = LongArray(0)
    private var values = DoubleArray(0)
    private var count = 0

    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeJoin = Paint.Join.ROUND
        color = themeColor(R.attr.appAccent)
    }
    private val grid = Paint().apply { color = themeColor(R.attr.appOutline); strokeWidth = 1 * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.attr.appMuted)
        textSize = 11 * resources.displayMetrics.scaledDensity
        typeface = TextView(context).typeface ?: Typeface.DEFAULT
    }
    private val path = Path()

    /** Replace the data (times in ms, newest last). */
    fun setData(t: LongArray, v: DoubleArray, n: Int) {
        times = t; values = v; count = n
        invalidate()
    }

    private fun themeColor(attr: Int): Int {
        val v = TypedValue()
        context.theme.resolveAttribute(attr, v, true)
        return v.data
    }

    override fun onDraw(canvas: Canvas) {
        val left = 44 * dp
        val right = width - 8 * dp
        val top = 10 * dp
        val bottom = height - 22 * dp
        if (count < 2) {
            canvas.drawText("Waiting for readings…", left, (top + bottom) / 2, label)
            return
        }
        val end = times[count - 1]
        val start = end - windowSeconds * 1000L
        var first = count - 1
        while (first > 0 && times[first - 1] >= start) first--
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in first until count) {
            lo = minOf(lo, values[i]); hi = maxOf(hi, values[i])
        }
        // Keep at least ±2% of range so a flat line sits in the middle.
        val pad = maxOf((hi - lo) * 0.15, hi * 0.02, 1.0)
        lo = maxOf(0.0, lo - pad); hi += pad

        // Grid: three horizontal lines with values.
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val y = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText(v.toInt().toString(), 0f, y + 4 * dp, label)
        }
        canvas.drawText("-${windowSeconds}s", left, height - 4 * dp, label)
        canvas.drawText("now", right - label.measureText("now"), height - 4 * dp, label)

        path.reset()
        for (i in first until count) {
            val x = (left + (times[i] - start).toFloat() / (windowSeconds * 1000f) * (right - left))
            val y = (bottom - (values[i] - lo) / (hi - lo) * (bottom - top)).toFloat()
            if (i == first) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }
}
