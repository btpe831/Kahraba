package com.kahraba.power

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * The Always-on light graph: the light level over time, its wobble as a soft band around the line,
 * and behind it what the app called (green kahraba, orange moteur, grey power off).
 */
class HistoryChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var slices: List<LightHistory.Slice> = emptyList()
    private var xLabels: List<String> = emptyList()
    var emptyText = "Nothing recorded yet."

    private val dp = resources.displayMetrics.density
    private val accent = themeColor(R.attr.appAccent)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val band = Paint().apply { color = (accent and 0x00FFFFFF) or 0x66000000 }
    private val shade = Paint()
    private val grid = Paint().apply { color = themeColor(R.attr.appOutline); strokeWidth = 1 * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(R.attr.appMuted)
        textSize = 11 * resources.displayMetrics.scaledDensity
        typeface = TextView(context).typeface ?: Typeface.DEFAULT
    }
    private val path = Path()

    /** How many slices to ask for: about one per 2 dp of width. */
    fun sliceCount(): Int = if (width <= 0) 150 else maxOf(60, (width / (2 * dp)).toInt())

    fun set(slices: List<LightHistory.Slice>, xLabels: List<String>) {
        this.slices = slices
        this.xLabels = xLabels
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
        val n = slices.size
        if (n == 0 || slices.none { it.recorded }) {
            canvas.drawText(emptyText, left, (top + bottom) / 2, label)
            return
        }
        val w = (right - left) / n
        fun x(i: Int) = left + i * w

        // What the app called, behind everything.
        for (i in 0 until n) {
            val s = slices[i].shade ?: continue
            shade.color = (MonitorService.colorFor(s) and 0x00FFFFFF) or 0x4D000000
            canvas.drawRect(x(i), top, x(i + 1) + 0.5f, bottom, shade)
        }

        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (s in slices) {
            s.minLux?.let { lo = minOf(lo, it) }
            s.maxLux?.let { hi = maxOf(hi, it) }
        }
        if (lo == Double.MAX_VALUE) {
            lo = 0.0; hi = 100.0
        }
        val pad = maxOf((hi - lo) * 0.12, hi * 0.02, 1.0)
        lo = maxOf(0.0, lo - pad); hi += pad
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText("%,d".format(java.util.Locale.US, Math.round(v)), 0f, yy + 4 * dp, label)
        }
        for ((k, text) in xLabels.withIndex()) {
            val tw = label.measureText(text)
            val at = left + (right - left) * k / maxOf(1, xLabels.size - 1)
            val tx = when (k) {
                0 -> at
                xLabels.lastIndex -> at - tw
                else -> at - tw / 2
            }
            canvas.drawText(text, tx, height - 4 * dp, label)
        }

        // The wobble: from the lowest to the highest light in each slice.
        for (i in 0 until n) {
            val s = slices[i]
            val a = s.minLux ?: continue
            val b = s.maxLux ?: continue
            canvas.drawRect(x(i), y(b) - 0.5f * dp, x(i + 1) + 0.5f, y(a) + 0.5f * dp, band)
        }
        // The light level, with gaps where there's nothing to draw.
        path.reset()
        var drawing = false
        for (i in 0 until n) {
            val m = slices[i].meanLux
            if (m == null) {
                drawing = false
                continue
            }
            val xx = x(i) + w / 2
            if (drawing) path.lineTo(xx, y(m)) else path.moveTo(xx, y(m))
            drawing = true
        }
        canvas.drawPath(path, line)
    }
}
