package com.kahraba.power

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A rounded card with a thin border and its title sitting in a gap in the top line
 * (e.g. ── TODAY ──). Colours and font come from the current theme.
 */
class NotchCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var title: String = ""
        set(value) { field = value; invalidate() }
    var chevron: Boolean = false
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val radius = 26 * dp
    private val strokeW = 1 * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
    private val gap = Paint()
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        context.obtainStyledAttributes(attrs, R.styleable.NotchCard).apply {
            title = getString(R.styleable.NotchCard_notchTitle) ?: ""
            chevron = getBoolean(R.styleable.NotchCard_notchChevron, false)
            recycle()
        }
        fill.color = themeColor(R.attr.appSurface)
        stroke.color = themeColor(R.attr.appBorder)
        gap.color = themeColor(R.attr.appBg)
        text.color = themeColor(R.attr.appAccent)
        text.textSize = 14 * resources.displayMetrics.scaledDensity
        text.letterSpacing = 0.16f
        // Same font as the rest of the theme, in bold.
        text.typeface = Typeface.create(TextView(context).typeface, Typeface.BOLD)
        if (paddingTop == 0 && paddingLeft == 0) {
            val p = (20 * dp).toInt()
            setPadding(p, (30 * dp).toInt(), p, p)
        }
    }

    private fun themeColor(attr: Int): Int {
        val v = TypedValue()
        context.theme.resolveAttribute(attr, v, true)
        return v.data
    }

    override fun onDraw(canvas: Canvas) {
        val top = 10 * dp
        rect.set(strokeW / 2, top, width - strokeW / 2, height - strokeW / 2)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        if (title.isNotEmpty()) {
            val label = title.uppercase() + if (chevron) "  ›" else ""
            val w = text.measureText(label)
            val cx = width / 2f
            val pad = 12 * dp
            // Break the border line behind the title.
            canvas.drawRect(cx - w / 2 - pad, top - strokeW * 2, cx + w / 2 + pad, top + strokeW * 2, gap)
            val fm = text.fontMetrics
            canvas.drawText(label, cx - w / 2, top - (fm.ascent + fm.descent) / 2, text)
        }
        super.onDraw(canvas)
    }
}
