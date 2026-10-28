package com.kahraba.power

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/**
 * A guided tour over a screen: dims everything except one highlighted view, with a short tip
 * beside it and Next / Skip. Blocks other taps while it's showing.
 */
class TourOverlay(
    private val activity: Activity,
    private val steps: List<Step>,
    private val onDone: () -> Unit,
) : FrameLayout(activity) {

    /** One stop: what to highlight (null highlights nothing), and how to bring it into view first. */
    class Step(val title: String, val text: String, val target: () -> View?, val prepare: () -> Unit = {})

    private val dp = resources.displayMetrics.density
    private var index = 0
    private val hole = RectF()
    private val path = Path()
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = color(R.attr.appAccent)
    }
    private val bubble = LinearLayout(activity)
    private val counter = TextView(activity)
    private val title = TextView(activity)
    private val text = TextView(activity)
    private val next = MaterialButton(activity)

    init {
        setWillNotDraw(false)
        isClickable = true // swallow taps meant for the screen underneath
        isFocusable = true

        bubble.orientation = LinearLayout.VERTICAL
        bubble.setPadding(px(20), px(16), px(20), px(12))
        bubble.background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(color(R.attr.appSurface))
            setStroke(px(1), color(R.attr.appBorder))
        }
        bubble.elevation = 6 * dp
        counter.textSize = 12f
        counter.letterSpacing = 0.1f
        counter.setTextColor(color(R.attr.appMuted))
        title.textSize = 20f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(color(R.attr.appText))
        title.setPadding(0, px(4), 0, 0)
        text.textSize = 15f
        text.setLineSpacing(3 * dp, 1f)
        text.setTextColor(color(R.attr.appText))
        text.setPadding(0, px(6), 0, 0)
        bubble.addView(counter)
        bubble.addView(title)
        bubble.addView(text)

        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(TextView(activity).apply {
            this.text = "Skip tour"
            textSize = 15f
            setTextColor(color(R.attr.appMuted))
            setPadding(0, px(12), px(12), px(12))
            setOnClickListener { finish() }
        })
        buttons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        next.cornerRadius = px(24)
        next.setOnClickListener { if (index < steps.size - 1) show(index + 1) else finish() }
        buttons.addView(next)
        bubble.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })

        addView(bubble, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(px(16), 0, px(16), 0) })
        bubble.visibility = INVISIBLE
    }

    fun start(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        show(0)
    }

    fun finish() {
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    private fun show(i: Int) {
        index = i
        val s = steps[i]
        counter.text = "${i + 1} OF ${steps.size}"
        title.text = s.title
        text.text = s.text
        next.text = if (i == steps.size - 1) "Got it" else "Next"
        s.prepare()
        // Give any scrolling a moment to finish before measuring where the target is.
        postDelayed({ if (index == i) place() }, 350)
    }

    private fun place() {
        val target = steps[index].target()
        if (target == null || !target.isShown || target.width == 0) {
            hole.setEmpty()
        } else {
            val t = IntArray(2)
            val me = IntArray(2)
            target.getLocationInWindow(t)
            getLocationInWindow(me)
            val pad = 8 * dp
            hole.set(
                t[0] - me[0] - pad, t[1] - me[1] - pad,
                t[0] - me[0] + target.width + pad, t[1] - me[1] + target.height + pad,
            )
        }
        // The tip goes below the highlight if it fits, otherwise above; centred if nothing is highlighted.
        bubble.measure(
            MeasureSpec.makeMeasureSpec(width - px(32), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val h = bubble.measuredHeight
        val gap = 14 * dp
        bubble.translationY = when {
            hole.isEmpty -> (height - h) / 2f
            hole.bottom + gap + h < height - gap -> hole.bottom + gap
            else -> (hole.top - gap - h).coerceAtLeast(gap)
        }
        bubble.visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val r = 22 * dp
        if (!hole.isEmpty) path.addRoundRect(hole, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        if (!hole.isEmpty) canvas.drawRoundRect(hole, r, r, ring)
    }

    private fun color(attr: Int) = TypedValue().also { activity.theme.resolveAttribute(attr, it, true) }.data
    private fun px(v: Int) = (v * dp).toInt()
}
